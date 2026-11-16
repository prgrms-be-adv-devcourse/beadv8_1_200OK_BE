package com.ok.delivery;

import com.ok.delivery.adapter.in.event.DeliveryInboundTopics;
import com.ok.delivery.adapter.out.persistence.ShipmentJpaRepository;
import com.ok.delivery.domain.Carrier;
import com.ok.delivery.domain.Shipment;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.ok.delivery.fixture.ShipmentFixture.NOW;
import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 주문이 Kafka로 보낸 구매확정을 배송 리스너가 받아 항목을 확정하고 거래완료로 바꾸는지, 처리하지 못한 메시지를 DLT로 옮기는지 확인 (#644)
// - 주문 코드는 쓰지 않고, 주문이 보낼 JSON(OrderProductConfirmedMessage 모양)을 테스트가 토픽에 직접 넣는다
// - 테스트 브로커는 파티션이 하나라 보낸 순서대로 처리된다. 뒤 메시지가 처리됐으면 앞 메시지도 처리가 끝난 것이다
class DeliveryPurchaseConfirmedKafkaIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    // 이 클래스 전용 번호대. 주문·주문 상품 번호와 송장번호가 테스트마다 겹치지 않게 한다
    private static final AtomicLong SEQ = new AtomicLong(9_646_000L);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    ShipmentJpaRepository shipmentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    private DeliveryTopicReader dltReader;

    @BeforeEach
    void setUp() {
        dltReader = new DeliveryTopicReader(kafkaConnectionDetails.getBootstrapServers(),
                DeliveryInboundTopics.ORDER_PRODUCT_CONFIRMED_DLT);
    }

    @AfterEach
    void tearDown() {
        dltReader.close();
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 구매확정을_Kafka로_받으면_항목을_확정하고_거래완료로_바꾼다() {
        // given
        long orderItemId = saveDeliveredShipment();

        // when
        sendConfirmed(orderItemId, confirmedMessage(orderItemId));

        // then
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(orderItemId)).isEqualTo("COMPLETED"));
        assertThat(confirmedOf(orderItemId)).isTrue();
    }

    @Test
    void 같은_구매확정을_두_번_받아도_거래완료_시각은_처음_그대로다() {
        // given: 처음 받은 구매확정이 처리될 때까지 기다림
        long orderItemId = saveDeliveredShipment();
        sendConfirmed(orderItemId, confirmedMessage(orderItemId));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(orderItemId)).isEqualTo("COMPLETED"));
        Timestamp completedAt = completedAtOf(orderItemId);

        // when: 같은 메시지를 다시 보내고, 뒤이어 다른 구매확정을 보냄
        sendConfirmed(orderItemId, confirmedMessage(orderItemId));
        long laterOrderItemId = saveDeliveredShipment();
        sendConfirmed(laterOrderItemId, confirmedMessage(laterOrderItemId));

        // then: 뒤 메시지가 처리됐으면 다시 보낸 메시지도 처리가 끝난 것이다. DLT로 가지 않았어야 한다
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(laterOrderItemId)).isEqualTo("COMPLETED"));
        assertThat(completedAtOf(orderItemId)).isEqualTo(completedAt);
        assertThat(dltReader.awaitValuesFor(String.valueOf(orderItemId), Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void 주문_상품_번호가_빠진_메시지는_다시_시도하지_않고_DLT로_옮기고_다음_메시지를_처리한다() {
        // given: 리스너가 파티션을 맡았는지 정상 메시지로 먼저 확인한다. 그래야 아래 시간 측정에 그룹 배정 대기가 섞이지 않는다
        awaitListenerReady();
        long badOrderItemId = SEQ.incrementAndGet();
        Map<String, Object> invalid = new HashMap<>(confirmedMessage(badOrderItemId));
        invalid.remove("orderItemId");
        long goodOrderItemId = saveDeliveredShipment();

        // when
        long startedAt = System.nanoTime();
        sendConfirmed(badOrderItemId, invalid);
        sendConfirmed(goodOrderItemId, confirmedMessage(goodOrderItemId));

        // then: 다시 시도했다면 1·2·4초를 기다린 뒤에야 DLT에 닿는다
        List<String> dlt = dltReader.awaitValuesFor(String.valueOf(badOrderItemId), TIMEOUT);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(dlt).hasSize(1);
        // DLT에는 받는 DTO 모양으로 다시 쓴 값이 간다. 사람이 주문을 찾을 수 있게 orderId가 남는다
        assertThat(dlt.getFirst()).contains("\"orderItemId\":null", "\"orderId\":" + badOrderItemId * 10);
        assertThat(elapsedMillis).isLessThan(5_000L);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(goodOrderItemId)).isEqualTo("COMPLETED"));
    }

    @Test
    void 배송에_없는_주문_상품의_구매확정은_건너뛰지_않고_DLT로_옮긴다() {
        // given
        awaitListenerReady();
        long unknownOrderItemId = SEQ.incrementAndGet();

        // when
        long startedAt = System.nanoTime();
        sendConfirmed(unknownOrderItemId, confirmedMessage(unknownOrderItemId));

        // then: 다시 해도 같은 결과라 다시 시도하지 않는다
        List<String> dlt = dltReader.awaitValuesFor(String.valueOf(unknownOrderItemId), TIMEOUT);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(dlt).hasSize(1);
        assertThat(elapsedMillis).isLessThan(5_000L);
    }

    private void awaitListenerReady() {
        long orderItemId = saveDeliveredShipment();
        sendConfirmed(orderItemId, confirmedMessage(orderItemId));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(orderItemId)).isEqualTo("COMPLETED"));
    }

    // 항목 하나짜리 배송 완료 shipment를 저장하고 그 주문 상품 번호를 돌려준다
    private long saveDeliveredShipment() {
        long orderItemId = SEQ.incrementAndGet();
        Shipment shipment = shipment(orderItemId * 10, 10L, item(orderItemId));
        shipment.dispatch(Carrier.CJ_LOGISTICS, "100" + orderItemId, NOW);
        shipment.deliver(NOW);
        shipmentRepository.save(shipment);
        return orderItemId;
    }

    // 주문이 보낼 JSON (order OrderProductConfirmedMessage). 배송이 읽지 않는 정산 금액 필드도 그대로 넣어 무시되는지 함께 본다
    private static Map<String, Object> confirmedMessage(long orderItemId) {
        return Map.of(
                "orderId", orderItemId * 10,
                "orderItemId", orderItemId,
                "sellerId", 10L,
                "totalItemPrice", 60_000L,
                "sellerShippingPrice", 3_000L);
    }

    private void sendConfirmed(long orderItemId, Map<String, Object> message) {
        kafkaTemplate.send(DeliveryInboundTopics.ORDER_PRODUCT_CONFIRMED, String.valueOf(orderItemId), message);
    }

    private String statusOf(long orderItemId) {
        return jdbcTemplate.queryForObject("""
                SELECT s.status FROM shipment s JOIN shipment_item i ON i.shipment_id = s.id
                WHERE i.order_item_id = ?""", String.class, orderItemId);
    }

    private boolean confirmedOf(long orderItemId) {
        return jdbcTemplate.queryForObject("SELECT confirmed FROM shipment_item WHERE order_item_id = ?", Boolean.class, orderItemId);
    }

    private Timestamp completedAtOf(long orderItemId) {
        return jdbcTemplate.queryForObject("""
                SELECT s.completed_at FROM shipment s JOIN shipment_item i ON i.shipment_id = s.id
                WHERE i.order_item_id = ?""", Timestamp.class, orderItemId);
    }
}
