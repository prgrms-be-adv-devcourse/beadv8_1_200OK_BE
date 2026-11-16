package com.ok.delivery;

import com.ok.delivery.adapter.out.event.DeliveryTopics;
import com.ok.delivery.adapter.out.event.FailableDeliveryMessageSender;
import com.ok.delivery.adapter.out.persistence.DeliveryOutboxJpaRepository;
import com.ok.delivery.adapter.out.persistence.ShipmentJpaRepository;
import com.ok.delivery.application.dto.AddressCommand;
import com.ok.delivery.application.dto.SellerShipmentCommand;
import com.ok.delivery.application.dto.ShipmentCreateCommand;
import com.ok.delivery.application.dto.ShipmentDispatchCommand;
import com.ok.delivery.application.dto.ShipmentItemCommand;
import com.ok.delivery.application.port.in.DeliveryOutboxRelayUseCase;
import com.ok.delivery.application.port.in.ShipmentUseCase;
import com.ok.delivery.domain.Carrier;
import com.ok.delivery.domain.DeliveryOutbox;
import com.ok.delivery.domain.DeliveryOutboxEventType;
import com.ok.delivery.domain.DeliveryOutboxStatus;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.fixture.ShipmentFixture;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeCarrierClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 배송 생성·출고·배송 완료가 커밋되면 outbox 줄이 남고, 발송 작업이 실제 Kafka 토픽에 orderId를 키로 한 메시지를 한 건 보내는지 확인 (#644)
// - 발송 스케줄러는 테스트에서 꺼져 있어(module-test.yml) 발송 유스케이스를 직접 부른다
// - 다른 테스트의 메시지와 섞이지 않게 테스트마다 다른 주문 번호를 쓰고 토픽을 키(orderId)로 골라 센다
class DeliveryOutboxKafkaIntegrationTest extends AbstractIntegrationTest {

    private static final int BATCH_SIZE = 100;
    // 다시 보낸 중복까지 세려고 이 시간 동안 계속 읽는다. 발송은 저장 확인까지 기다린 뒤 끝나므로 정상이면 바로 읽힌다
    private static final Duration READ_DURATION = Duration.ofSeconds(3);
    private static final long SELLER_ID = 10L;
    // 이 클래스 전용 번호대. 주문 번호는 메시지 키, 주문 상품 번호와 송장번호는 유니크 제약이라 테스트마다 겹치지 않게 한다
    private static final AtomicLong SEQ = new AtomicLong(9_644_000L);

    @Autowired
    ShipmentUseCase shipmentUseCase;

    @Autowired
    DeliveryOutboxRelayUseCase deliveryOutboxRelayUseCase;

    @Autowired
    ShipmentJpaRepository shipmentRepository;

    @Autowired
    DeliveryOutboxJpaRepository deliveryOutboxRepository;

    @Autowired
    FailableDeliveryMessageSender failableDeliveryMessageSender;

    @Autowired
    FakeCarrierClient fakeCarrierClient;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    private DeliveryTopicReader createdReader;
    private DeliveryTopicReader dispatchedReader;
    private DeliveryTopicReader deliveredReader;

    // 다른 테스트가 출고하며 남긴 줄이 이 테스트의 발송 건수에 섞이지 않게 비우고 시작한다
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM delivery_outbox");
        createdReader = new DeliveryTopicReader(kafkaConnectionDetails.getBootstrapServers(), DeliveryTopics.SHIPMENT_CREATED);
        dispatchedReader = new DeliveryTopicReader(kafkaConnectionDetails.getBootstrapServers(), DeliveryTopics.SHIPMENT_DISPATCHED);
        deliveredReader = new DeliveryTopicReader(kafkaConnectionDetails.getBootstrapServers(), DeliveryTopics.SHIPMENT_DELIVERED);
    }

    // 걸어 둔 실패가 다른 테스트로 넘어가지 않게 지운다
    @AfterEach
    void tearDown() {
        failableDeliveryMessageSender.reset();
        createdReader.close();
        dispatchedReader.close();
        deliveredReader.close();
        jdbcTemplate.update("DELETE FROM delivery_outbox");
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 판매자_둘의_배송이_만들어지고_발송_작업이_돌면_판매자마다_배송_생성_메시지가_orderId를_키로_나간다() {
        // given
        long orderId = SEQ.incrementAndGet();
        shipmentUseCase.create(createCommand(orderId));
        assertThat(deliveryOutboxRepository.findAll())
                .extracting(DeliveryOutbox::getEventType, DeliveryOutbox::getMessageKey)
                .containsExactly(
                        tuple(DeliveryOutboxEventType.SHIPMENT_CREATED, String.valueOf(orderId)),
                        tuple(DeliveryOutboxEventType.SHIPMENT_CREATED, String.valueOf(orderId)));

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then: 받는 쪽(주문)과 약속한 JSON 필드 그대로. 판매자 순으로 맞춰 본다
        assertThat(sent).isEqualTo(2);
        List<JsonNode> sut = createdReader.readValuesFor(String.valueOf(orderId), READ_DURATION).stream()
                .map(jsonMapper::readTree)
                .sorted(Comparator.comparingLong(message -> message.get("sellerId").asLong()))
                .toList();
        assertThat(sut).hasSize(2);
        List<Shipment> shipments = shipmentsOf(orderId);
        assertThat(shipments).hasSize(2);
        assertSoftly(softly -> {
            for (int i = 0; i < 2; i++) {
                JsonNode message = sut.get(i);
                Shipment shipment = shipments.get(i);
                softly.assertThat(message.propertyNames()).containsExactlyInAnyOrder(
                        "shipmentId", "orderId", "sellerId", "orderItemIds", "createdAt");
                softly.assertThat(message.get("shipmentId").asLong()).isEqualTo(shipment.getId());
                softly.assertThat(message.get("orderId").asLong()).isEqualTo(orderId);
                softly.assertThat(message.get("sellerId").asLong()).isEqualTo(shipment.getSellerId());
                // 저장된 배송 생성 시각과 같아야 한다. DB는 마이크로초까지만 저장해 그 차이만 허용한다
                softly.assertThat(LocalDateTime.parse(message.get("createdAt").asString()))
                        .isCloseTo(shipment.getCreatedAt(), within(1, ChronoUnit.MICROS));
            }
            softly.assertThat(sut.get(0).get("orderItemIds").toString()).isEqualTo("[%d]".formatted(orderId * 10 + 1));
            softly.assertThat(sut.get(1).get("orderItemIds").toString())
                    .isEqualTo("[%d,%d]".formatted(orderId * 10 + 2, orderId * 10 + 3));
        });
        assertThat(deliveryOutboxRepository.findAll()).extracting(DeliveryOutbox::getStatus)
                .containsOnly(DeliveryOutboxStatus.SENT);
    }

    @Test
    void 같은_결제_완료를_두_번_처리해도_배송_생성_줄은_판매자마다_한_건이다() {
        // given
        long orderId = SEQ.incrementAndGet();
        shipmentUseCase.create(createCommand(orderId));

        // when
        shipmentUseCase.create(createCommand(orderId));

        // then
        assertThat(deliveryOutboxRepository.findAll()).hasSize(2);
    }

    @Test
    void 배송_생성_트랜잭션이_롤백되면_메시지가_나가지_않는다() {
        // given
        long orderId = SEQ.incrementAndGet();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentUseCase.create(createCommand(orderId));
            status.setRollbackOnly();
        });

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isZero();
        assertThat(deliveryOutboxRepository.findAll()).isEmpty();
        assertThat(shipmentsOf(orderId)).isEmpty();
        assertThat(createdReader.readValuesFor(String.valueOf(orderId), READ_DURATION)).isEmpty();
    }

    @Test
    void 출고가_커밋되면_배송_시작을_보낼_줄이_outbox에_남는다() {
        // given
        Shipment shipment = savePreparing();

        // when
        shipmentUseCase.dispatch(dispatchCommand(shipment));

        // then
        List<DeliveryOutbox> outboxes = deliveryOutboxRepository.findAll();
        assertThat(outboxes).hasSize(1);
        DeliveryOutbox sut = outboxes.getFirst();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getEventType()).isEqualTo(DeliveryOutboxEventType.SHIPMENT_DISPATCHED);
            softly.assertThat(sut.getMessageKey()).isEqualTo(String.valueOf(shipment.getOrderId()));
            softly.assertThat(sut.getStatus()).isEqualTo(DeliveryOutboxStatus.PENDING);
            softly.assertThat(sut.getAttemptCount()).isZero();
        });
    }

    @Test
    void 발송_작업이_돌면_orderId를_키로_한_배송_시작_메시지가_한_건_나가고_줄이_보냄이_된다() {
        // given
        Shipment shipment = savePreparing();
        String trackingNumber = shipmentUseCase.dispatch(dispatchCommand(shipment)).trackingNumber();

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then: 받는 쪽(주문)과 약속한 JSON 필드 그대로. 문자열로 한 번 더 감싸지 않은 객체 JSON이어야 한다
        assertThat(sent).isEqualTo(1);
        List<String> values = dispatchedReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION);
        assertThat(values).hasSize(1);
        JsonNode sut = jsonMapper.readTree(values.getFirst());
        assertThat(sut.isObject()).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(sut.propertyNames()).containsExactlyInAnyOrder(
                    "shipmentId", "orderId", "sellerId", "carrier", "trackingNumber", "shippedAt");
            softly.assertThat(sut.get("shipmentId").asLong()).isEqualTo(shipment.getId());
            softly.assertThat(sut.get("orderId").asLong()).isEqualTo(shipment.getOrderId());
            softly.assertThat(sut.get("sellerId").asLong()).isEqualTo(SELLER_ID);
            softly.assertThat(sut.get("carrier").asString()).isEqualTo("CJ_LOGISTICS");
            softly.assertThat(sut.get("trackingNumber").asString()).isEqualTo(trackingNumber);
            // 오프셋 없는 ISO-8601 (#644 메시지 약속)
            softly.assertThat(sut.get("shippedAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
        });
        assertThat(deliveryOutboxRepository.findAll().getFirst().getStatus()).isEqualTo(DeliveryOutboxStatus.SENT);
    }

    @Test
    void 배송_완료가_커밋되고_발송_작업이_돌면_orderId를_키로_한_배송_완료_메시지가_한_건_나간다() {
        // given
        Shipment shipment = saveShipping();
        shipmentUseCase.deliver(shipment.getId());

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then: 받는 쪽(주문)과 약속한 JSON 필드 그대로
        assertThat(sent).isEqualTo(1);
        List<String> values = deliveredReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION);
        assertThat(values).hasSize(1);
        JsonNode sut = jsonMapper.readTree(values.getFirst());
        assertThat(sut.isObject()).isTrue();
        LocalDateTime deliveredAt = shipmentRepository.findById(shipment.getId()).orElseThrow().getDeliveredAt();
        assertSoftly(softly -> {
            softly.assertThat(sut.propertyNames()).containsExactlyInAnyOrder("shipmentId", "orderId", "sellerId", "deliveredAt");
            softly.assertThat(sut.get("shipmentId").asLong()).isEqualTo(shipment.getId());
            softly.assertThat(sut.get("orderId").asLong()).isEqualTo(shipment.getOrderId());
            softly.assertThat(sut.get("sellerId").asLong()).isEqualTo(SELLER_ID);
            // 주문의 자동 구매확정 기준이라 저장된 배송 완료 시각과 같아야 한다. DB는 마이크로초까지만 저장해 그 차이만 허용한다
            softly.assertThat(LocalDateTime.parse(sut.get("deliveredAt").asString()))
                    .isCloseTo(deliveredAt, within(1, ChronoUnit.MICROS));
        });
        assertThat(deliveryOutboxRepository.findAll().getFirst().getStatus()).isEqualTo(DeliveryOutboxStatus.SENT);
    }

    @Test
    void 배송_완료_트랜잭션이_롤백되면_메시지가_나가지_않는다() {
        // given
        Shipment shipment = saveShipping();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentUseCase.deliver(shipment.getId());
            status.setRollbackOnly();
        });

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isZero();
        assertThat(deliveryOutboxRepository.findAll()).isEmpty();
        assertThat(deliveredReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION)).isEmpty();
    }

    @Test
    void 같은_주문의_출고와_배송_완료는_남긴_순서대로_한_번의_실행에서_모두_나간다() {
        // given
        Shipment shipment = savePreparing();
        shipmentUseCase.dispatch(dispatchCommand(shipment));
        shipmentUseCase.deliver(shipment.getId());

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then: 발송 작업은 id 순서로 보내므로 남긴 순서가 곧 보낸 순서다
        assertThat(sent).isEqualTo(2);
        assertThat(deliveryOutboxRepository.findAll(Sort.by("id")))
                .extracting(DeliveryOutbox::getEventType, DeliveryOutbox::getStatus)
                .containsExactly(
                        tuple(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, DeliveryOutboxStatus.SENT),
                        tuple(DeliveryOutboxEventType.SHIPMENT_DELIVERED, DeliveryOutboxStatus.SENT));
        String orderKey = String.valueOf(shipment.getOrderId());
        assertThat(dispatchedReader.readValuesFor(orderKey, READ_DURATION)).hasSize(1);
        assertThat(deliveredReader.readValuesFor(orderKey, READ_DURATION)).hasSize(1);
    }

    @Test
    void 보낸_줄은_다음_실행에서_다시_보내지_않는다() {
        // given
        Shipment shipment = savePreparing();
        shipmentUseCase.dispatch(dispatchCommand(shipment));
        deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isZero();
        assertThat(dispatchedReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION)).hasSize(1);
    }

    @Test
    void 출고_트랜잭션이_롤백되면_outbox_줄이_남지_않아_메시지가_나가지_않는다() {
        // given: 출고를 바깥 트랜잭션 안에서 부르고 끝에 롤백한다 (출고는 바깥 트랜잭션에 합류한다)
        Shipment shipment = savePreparing();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentUseCase.dispatch(dispatchCommand(shipment));
            status.setRollbackOnly();
        });

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isZero();
        assertThat(deliveryOutboxRepository.findAll()).isEmpty();
        assertThat(dispatchedReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION)).isEmpty();
    }

    @Test
    void 브로커가_일시적으로_실패하면_실패를_기록하고_다음_실행에서_다시_보내_메시지가_한_건_나간다() {
        // given: 테스트가 함께 쓰는 Kafka 컨테이너는 멈출 수 없어 첫 발송만 실패시킨다
        Shipment shipment = savePreparing();
        shipmentUseCase.dispatch(dispatchCommand(shipment));
        failableDeliveryMessageSender.failNextSend(
                new IllegalStateException("배송 Kafka 발행에 실패했습니다.", new RuntimeException("브로커 일시 장애")));
        int sentWhileFailing = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);
        DeliveryOutbox failed = deliveryOutboxRepository.findAll().getFirst();
        assertThat(failed).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sentWhileFailing).isZero();
            softly.assertThat(failed.getStatus()).isEqualTo(DeliveryOutboxStatus.PENDING);
            softly.assertThat(failed.getAttemptCount()).isEqualTo(1);
            softly.assertThat(failed.getLastError()).contains("브로커 일시 장애");
        });

        // when: 브로커가 복구된 뒤 다음 실행
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isEqualTo(1);
        assertThat(dispatchedReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION)).hasSize(1);
        assertThat(deliveryOutboxRepository.findAll().getFirst().getStatus()).isEqualTo(DeliveryOutboxStatus.SENT);
    }

    @Test
    void 출고가_커밋된_뒤에만_택배사에_추적을_요청하고_롤백된_출고는_요청하지_않는다() {
        // given: 롤백된 출고 하나와 커밋된 출고 하나
        Shipment rolledBack = savePreparing();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentUseCase.dispatch(dispatchCommand(rolledBack));
            status.setRollbackOnly();
        });
        Shipment committed = savePreparing();

        // when
        shipmentUseCase.dispatch(dispatchCommand(committed));

        // then: 요청은 커밋 뒤 다른 스레드에서 가므로 기다린다. 롤백된 출고의 요청은 먼저 일어났더라도 이때까지 오지 않았어야 한다
        await().atMost(Duration.ofSeconds(10)).until(() -> fakeCarrierClient.isRequested(committed.getId()));
        assertThat(fakeCarrierClient.isRequested(rolledBack.getId())).isFalse();
    }

    // 판매자 10은 주문 상품 1개, 판매자 20은 2개. 주문 상품 번호는 유니크라 주문 번호로 만든다
    private static ShipmentCreateCommand createCommand(long orderId) {
        AddressCommand address = new AddressCommand("홍길동", "010-1234-5678", "06236", "주소", "상세 주소", null);
        return new ShipmentCreateCommand(orderId, 100L, ShipmentFixture.NOW, address, List.of(
                new SellerShipmentCommand(10L, 3_000L, 50_000L, 0L, List.of(itemCommand(orderId * 10 + 1))),
                new SellerShipmentCommand(20L, 3_000L, 50_000L, 0L,
                        List.of(itemCommand(orderId * 10 + 2), itemCommand(orderId * 10 + 3)))));
    }

    private static ShipmentItemCommand itemCommand(long orderItemId) {
        return new ShipmentItemCommand(orderItemId, "상품", null, 1, 10_000L);
    }

    private List<Shipment> shipmentsOf(long orderId) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM shipment WHERE order_id = ?", Long.class, orderId);
        return shipmentRepository.findAllById(ids).stream()
                .sorted(Comparator.comparing(Shipment::getSellerId))
                .toList();
    }

    private Shipment savePreparing() {
        long orderId = SEQ.incrementAndGet();
        return shipmentRepository.save(ShipmentFixture.shipment(orderId, SELLER_ID, ShipmentFixture.item(orderId)));
    }

    private Shipment saveShipping() {
        long orderId = SEQ.incrementAndGet();
        Shipment shipment = ShipmentFixture.shipment(orderId, SELLER_ID, ShipmentFixture.item(orderId));
        shipment.dispatch(Carrier.CJ_LOGISTICS, trackingNumber(orderId), ShipmentFixture.NOW);
        return shipmentRepository.save(shipment);
    }

    private static ShipmentDispatchCommand dispatchCommand(Shipment shipment) {
        return new ShipmentDispatchCommand(shipment.getId(), SELLER_ID, "CJ_LOGISTICS", trackingNumber(shipment.getOrderId()));
    }

    // 송장번호는 숫자 10~13자리이고 (택배사, 송장번호)가 유니크라 주문 번호로 만든다
    private static String trackingNumber(long orderId) {
        return "100" + orderId;
    }
}
