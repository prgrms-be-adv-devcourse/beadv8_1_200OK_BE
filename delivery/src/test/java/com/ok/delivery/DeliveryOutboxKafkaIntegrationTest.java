package com.ok.delivery;

import com.ok.common.exception.RestApiException;
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
import com.ok.delivery.config.DeliveryAsyncConfig;
import com.ok.delivery.domain.Carrier;
import com.ok.delivery.domain.DeliveryOutbox;
import com.ok.delivery.domain.DeliveryOutboxEventType;
import com.ok.delivery.domain.DeliveryOutboxStatus;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.fixture.ShipmentFixture;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeCarrierClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 배송 생성·출고·배송 완료가 커밋되면 outbox 줄이 남고, 커밋 직후 실제 Kafka 토픽에 orderId를 키로 한 메시지가 한 건 나가는지,
// 그때 못 보낸 줄을 발송 작업이 다시 보내는지 확인 (#644)
// - 발송 스케줄러는 테스트에서 꺼져 있어(module-test.yml) 커밋 직후 나간 메시지는 즉시 발송이 보낸 것이다. 발송 작업은 유스케이스를 직접 부른다
// - 발송 작업만 보려는 테스트는 outbox 줄을 저장소로 직접 남긴다. 그러면 즉시 발송 이벤트가 나가지 않는다
// - 다른 테스트의 메시지와 섞이지 않게 테스트마다 다른 주문 번호를 쓰고 토픽을 키(orderId)로 골라 센다
class DeliveryOutboxKafkaIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutboxKafkaIntegrationTest.class);
    private static final int BATCH_SIZE = 100;
    // 발송 작업만 보는 테스트에서 방금 남긴 줄도 고르게 한다
    private static final Duration NO_MIN_AGE = Duration.ZERO;
    // 즉시 발송이 이 안에 도착해야 한다. 실제로는 수십 ms라 넉넉히 둔다
    private static final Duration IMMEDIATE_SEND_TIMEOUT = Duration.ofSeconds(5);
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

    @Autowired
    @Qualifier(DeliveryAsyncConfig.OUTBOX_SEND_EXECUTOR)
    AsyncTaskExecutor outboxSendExecutor;

    @Autowired
    MeterRegistry meterRegistry;

    private DeliveryTopicReader createdReader;
    private DeliveryTopicReader dispatchedReader;
    private DeliveryTopicReader deliveredReader;

    // 다른 테스트가 출고하며 남긴 줄이 이 테스트의 발송 건수에 섞이지 않게 비우고 시작한다.
    // 앞 테스트(다른 클래스 포함)의 즉시 발송이 늦게 돌아 이 테스트의 failNextSend를 먼저 쓰지 않게 끝나기를 기다린다
    @BeforeEach
    void setUp() {
        awaitOutboxSendIdle();
        jdbcTemplate.update("DELETE FROM delivery_outbox");
        createdReader = new DeliveryTopicReader(kafkaConnectionDetails.getBootstrapServers(), DeliveryTopics.SHIPMENT_CREATED);
        dispatchedReader = new DeliveryTopicReader(kafkaConnectionDetails.getBootstrapServers(), DeliveryTopics.SHIPMENT_DISPATCHED);
        deliveredReader = new DeliveryTopicReader(kafkaConnectionDetails.getBootstrapServers(), DeliveryTopics.SHIPMENT_DELIVERED);
    }

    // 걸어 둔 실패가 다른 테스트로 넘어가지 않게 지운다
    @AfterEach
    void tearDown() {
        // 지운 줄에 실패 기록이 닿아 비동기 스레드에서 예외가 나지 않게, 남은 즉시 발송이 끝난 뒤 지운다
        awaitOutboxSendIdle();
        failableDeliveryMessageSender.reset();
        createdReader.close();
        dispatchedReader.close();
        deliveredReader.close();
        jdbcTemplate.update("DELETE FROM delivery_outbox");
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 판매자_둘의_배송이_만들어지면_커밋_직후_판매자마다_배송_생성_메시지가_orderId를_키로_나간다() {
        // given
        long orderId = SEQ.incrementAndGet();

        // when
        shipmentUseCase.create(createCommand(orderId));

        // then: 받는 쪽(주문)과 약속한 JSON 필드 그대로. 판매자 순으로 맞춰 본다
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
        awaitAllSent();
        assertThat(deliveryOutboxRepository.findAll())
                .extracting(DeliveryOutbox::getEventType, DeliveryOutbox::getMessageKey)
                .containsExactly(
                        tuple(DeliveryOutboxEventType.SHIPMENT_CREATED, String.valueOf(orderId)),
                        tuple(DeliveryOutboxEventType.SHIPMENT_CREATED, String.valueOf(orderId)));
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

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentUseCase.create(createCommand(orderId));
            status.setRollbackOnly();
        });

        // then
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

        // then: 상태는 즉시 발송이 곧 바꾸므로 여기서 보지 않는다
        List<DeliveryOutbox> outboxes = deliveryOutboxRepository.findAll();
        assertThat(outboxes).hasSize(1);
        DeliveryOutbox sut = outboxes.getFirst();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getEventType()).isEqualTo(DeliveryOutboxEventType.SHIPMENT_DISPATCHED);
            softly.assertThat(sut.getMessageKey()).isEqualTo(String.valueOf(shipment.getOrderId()));
        });
    }

    @Test
    void 출고가_커밋되면_발송_작업_없이_바로_배송_전용_실행기에서_배송_시작_메시지가_한_건_나가고_줄이_보냄이_된다() {
        // given
        Shipment shipment = savePreparing();
        String orderKey = String.valueOf(shipment.getOrderId());

        // when
        String trackingNumber = shipmentUseCase.dispatch(dispatchCommand(shipment)).trackingNumber();
        long committedAt = System.nanoTime();
        List<String> arrived = dispatchedReader.awaitValuesFor(orderKey, IMMEDIATE_SEND_TIMEOUT);
        Duration commitToArrival = Duration.ofNanos(System.nanoTime() - committedAt);

        // then: 받는 쪽(주문)과 약속한 JSON 필드 그대로. 문자열로 한 번 더 감싼 값이 아닌 객체 JSON이어야 한다
        log.info("배송 시작 즉시 발송: 커밋 뒤 토픽 도착까지 {}ms", commitToArrival.toMillis());
        assertThat(arrived).hasSize(1);
        List<String> values = dispatchedReader.readValuesFor(orderKey, READ_DURATION);
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
        // 커밋 뒤 콜백은 요청 스레드에서 돌므로, 요청 스레드가 브로커 확인을 기다리지 않았는지 보낸 스레드로 확인한다
        assertThat(failableDeliveryMessageSender.lastSendThreadName()).startsWith("delivery-outbox-");
        awaitAllSent();
    }

    @Test
    void 배송_완료가_커밋되면_바로_orderId를_키로_한_배송_완료_메시지가_한_건_나간다() {
        // given
        Shipment shipment = saveShipping();

        // when
        shipmentUseCase.deliver(shipment.getId());

        // then: 받는 쪽(주문)과 약속한 JSON 필드 그대로
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
        awaitAllSent();
    }

    @Test
    void 배송_완료_트랜잭션이_롤백되면_메시지가_나가지_않는다() {
        // given
        Shipment shipment = saveShipping();

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentUseCase.deliver(shipment.getId());
            status.setRollbackOnly();
        });

        // then
        assertThat(deliveryOutboxRepository.findAll()).isEmpty();
        assertThat(deliveredReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION)).isEmpty();
    }

    @Test
    void 출고_트랜잭션이_롤백되면_outbox_줄이_남지_않아_메시지가_나가지_않는다() {
        // given: 출고를 바깥 트랜잭션 안에서 부르고 끝에 롤백한다 (출고는 바깥 트랜잭션에 합류한다)
        Shipment shipment = savePreparing();

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentUseCase.dispatch(dispatchCommand(shipment));
            status.setRollbackOnly();
        });

        // then
        assertThat(deliveryOutboxRepository.findAll()).isEmpty();
        assertThat(dispatchedReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION)).isEmpty();
    }

    @Test
    void 즉시_발송이_실패하면_줄은_PENDING으로_남아_실패가_기록되고_발송_작업이_다시_보내_메시지가_한_건_나간다() {
        // given: 테스트가 함께 쓰는 Kafka 컨테이너는 멈출 수 없어 첫 발송(즉시 발송)만 실패시킨다
        Shipment shipment = savePreparing();
        failableDeliveryMessageSender.failNextSend(
                new IllegalStateException("배송 Kafka 발행에 실패했습니다.", new RuntimeException("브로커 일시 장애")));
        shipmentUseCase.dispatch(dispatchCommand(shipment));
        await().atMost(IMMEDIATE_SEND_TIMEOUT)
                .until(() -> deliveryOutboxRepository.findAll().getFirst().getAttemptCount() == 1);
        DeliveryOutbox failed = deliveryOutboxRepository.findAll().getFirst();
        assertThat(failed).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(failed.getStatus()).isEqualTo(DeliveryOutboxStatus.PENDING);
            softly.assertThat(failed.getLastError()).contains("브로커 일시 장애");
        });

        // when: 브로커가 복구된 뒤 발송 작업
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE, NO_MIN_AGE);

        // then
        assertThat(sent).isEqualTo(1);
        assertThat(dispatchedReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION)).hasSize(1);
        assertThat(deliveryOutboxRepository.findAll().getFirst().getStatus()).isEqualTo(DeliveryOutboxStatus.SENT);
    }

    @Test
    void 즉시_발송_대기열이_가득_차면_출고는_기다리지_않고_성공하고_줄은_PENDING으로_남아_발송_작업이_보낸다() {
        // given: 발송 스레드를 모두 붙잡은 뒤 대기열을 채운다
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) outboxSendExecutor;
        CountDownLatch release = new CountDownLatch(1);
        Shipment shipment = savePreparing();
        double rejectedBefore = rejectedCount();
        try {
            for (int i = 0; i < executor.getMaxPoolSize(); i++) {
                executor.execute(() -> awaitLatch(release));
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> executor.getActiveCount() == executor.getMaxPoolSize());
            for (int i = 0; i < executor.getQueueCapacity(); i++) {
                executor.execute(() -> { });
            }

            // when
            shipmentUseCase.dispatch(dispatchCommand(shipment));

            // then: 출고가 돌아왔을 때 이미 버린 것으로 세어져 있다
            assertThat(rejectedCount()).isEqualTo(rejectedBefore + 1);
            assertThat(deliveryOutboxRepository.findAll().getFirst().getStatus()).isEqualTo(DeliveryOutboxStatus.PENDING);
        } finally {
            release.countDown();
        }
        awaitOutboxSendIdle();
        assertThat(deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE, NO_MIN_AGE)).isEqualTo(1);
        assertThat(dispatchedReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION)).hasSize(1);
        assertThat(deliveryOutboxRepository.findAll().getFirst().getStatus()).isEqualTo(DeliveryOutboxStatus.SENT);
    }

    @Test
    void 즉시_발송이_보낸_줄은_발송_작업이_다시_보내지_않는다() {
        // given
        Shipment shipment = savePreparing();
        shipmentUseCase.dispatch(dispatchCommand(shipment));
        awaitAllSent();

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE, NO_MIN_AGE);

        // then
        assertThat(sent).isZero();
        assertThat(dispatchedReader.readValuesFor(String.valueOf(shipment.getOrderId()), READ_DURATION)).hasSize(1);
    }

    @Test
    void 발송_작업은_남은_지_최소_시간이_지나지_않은_줄을_보내지_않는다() {
        // given: 즉시 발송이 아직 보내는 중일 수 있는 줄
        long orderId = SEQ.incrementAndGet();
        DeliveryOutbox outbox = savePendingDirectly(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, orderId);

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE, Duration.ofMinutes(1));

        // then
        assertThat(sent).isZero();
        assertThat(deliveryOutboxRepository.findById(outbox.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryOutboxStatus.PENDING);
        assertThat(dispatchedReader.readValuesFor(String.valueOf(orderId), READ_DURATION)).isEmpty();
    }

    @Test
    void 발송_작업은_남은_줄을_쌓인_순서대로_한_번의_실행에서_모두_보낸다() {
        // given
        long orderId = SEQ.incrementAndGet();
        savePendingDirectly(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, orderId);
        savePendingDirectly(DeliveryOutboxEventType.SHIPMENT_DELIVERED, orderId);

        // when
        int sent = deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE, NO_MIN_AGE);

        // then
        assertThat(sent).isEqualTo(2);
        assertThat(deliveryOutboxRepository.findAll(Sort.by("id")))
                .extracting(DeliveryOutbox::getEventType, DeliveryOutbox::getStatus)
                .containsExactly(
                        tuple(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, DeliveryOutboxStatus.SENT),
                        tuple(DeliveryOutboxEventType.SHIPMENT_DELIVERED, DeliveryOutboxStatus.SENT));
        String orderKey = String.valueOf(orderId);
        assertThat(dispatchedReader.readValuesFor(orderKey, READ_DURATION)).hasSize(1);
        assertThat(deliveredReader.readValuesFor(orderKey, READ_DURATION)).hasSize(1);
    }

    @Test
    void 이미_보냄인_줄을_다시_보냄으로_바꾸면_바뀐_줄이_없어_중복_발송을_알아챈다() {
        // given: 즉시 발송과 발송 작업이 같은 줄을 함께 보낸 경우. 먼저 끝난 쪽이 보냄으로 바꿨다
        long orderId = SEQ.incrementAndGet();
        Long outboxId = savePendingDirectly(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, orderId).getId();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        LocalDateTime sentAt = LocalDateTime.now();
        Integer first = transaction.execute(status -> deliveryOutboxRepository.markSentIfPending(outboxId, sentAt));

        // when
        Integer sut = transaction.execute(status -> deliveryOutboxRepository.markSentIfPending(outboxId, sentAt.plusSeconds(1)));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(first).isEqualTo(1);
            softly.assertThat(sut).isZero();
        });
    }

    @Test
    void 실패를_기록하려고_읽은_사이에_다른_발송이_보냄으로_바꿔도_실패_기록이_보냄을_되돌리지_않는다() throws Exception {
        // given: 즉시 발송이 실패를 기록하려고 줄을 읽은 뒤, 발송 작업이 같은 줄을 보내고 보냄으로 커밋한 경우
        long orderId = SEQ.incrementAndGet();
        Long outboxId = savePendingDirectly(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, orderId).getId();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch sentCommitted = new CountDownLatch(1);
        CompletableFuture<Void> failureRecording = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            DeliveryOutbox stale = deliveryOutboxRepository.findById(outboxId).orElseThrow();
            loaded.countDown();
            awaitLatch(sentCommitted);
            stale.recordFailure("브로커 확인 대기 시간 초과");
        }));
        awaitLatch(loaded);
        transaction.execute(status -> deliveryOutboxRepository.markSentIfPending(outboxId, LocalDateTime.now()));
        sentCommitted.countDown();

        // when
        failureRecording.get(10, TimeUnit.SECONDS);

        // then: 읽은 값 전체를 덮어쓰면 status가 PENDING으로 돌아가 한 번 더 나간다
        DeliveryOutbox sut = deliveryOutboxRepository.findById(outboxId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(DeliveryOutboxStatus.SENT);
            softly.assertThat(sut.getSentAt()).isNotNull();
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
        });
    }

    @Test
    void 이미_보낸_줄은_즉시_발송을_다시_불러도_보내지_않는다() {
        // given
        long orderId = SEQ.incrementAndGet();
        Long outboxId = savePendingDirectly(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, orderId).getId();
        deliveryOutboxRelayUseCase.relayPending(BATCH_SIZE, NO_MIN_AGE);

        // when
        deliveryOutboxRelayUseCase.send(outboxId);

        // then
        assertThat(dispatchedReader.readValuesFor(String.valueOf(orderId), READ_DURATION)).hasSize(1);
    }

    @Test
    void 출고가_커밋되면_택배사에_추적을_요청하고_실패해_롤백된_출고는_요청하지_않는다() {
        // given: 같은 송장번호로 두 번째 출고를 하면 송장번호 중복으로 롤백된다
        Shipment committed = savePreparing();
        Shipment rolledBack = savePreparing();
        shipmentUseCase.dispatch(dispatchCommand(committed));
        ShipmentDispatchCommand duplicated = new ShipmentDispatchCommand(
                rolledBack.getId(), SELLER_ID, "CJ_LOGISTICS", trackingNumber(committed.getOrderId()));

        // when & then
        assertThatThrownBy(() -> shipmentUseCase.dispatch(duplicated))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.TRACKING_NUMBER_DUPLICATED));
        assertThat(fakeCarrierClient.isRequested(committed.getId())).isTrue();
        assertThat(fakeCarrierClient.isRequested(rolledBack.getId())).isFalse();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private double rejectedCount() {
        return meterRegistry.get(DeliveryAsyncConfig.OUTBOX_SEND_DROPPED_METRIC)
                .tag("reason", DeliveryAsyncConfig.DROP_REASON_REJECTED)
                .counter()
                .count();
    }

    private void awaitOutboxSendIdle() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) outboxSendExecutor;
        await().atMost(Duration.ofSeconds(15))
                .until(() -> executor.getActiveCount() == 0 && executor.getQueueSize() == 0);
    }

    // 즉시 발송은 다른 스레드에서 끝나므로 보냄 기록까지 기다린다
    private void awaitAllSent() {
        await().atMost(IMMEDIATE_SEND_TIMEOUT).until(() -> deliveryOutboxRepository.findAll().stream()
                .allMatch(outbox -> outbox.getStatus() == DeliveryOutboxStatus.SENT));
    }

    // 발행 구현체를 거치지 않아 즉시 발송 이벤트가 나가지 않는다. 발송 작업만 보려는 테스트가 쓴다
    private DeliveryOutbox savePendingDirectly(DeliveryOutboxEventType eventType, long orderId) {
        return deliveryOutboxRepository.save(
                DeliveryOutbox.pending(eventType, String.valueOf(orderId), "{\"orderId\":%d}".formatted(orderId)));
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
