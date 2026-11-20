package com.ok.delivery;

import com.ok.delivery.adapter.in.event.DeliveryInboundTopics;
import com.ok.testsupport.AbstractIntegrationTest;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 주문이 Kafka로 보낸 결제 완료를 배송 리스너가 받아 판매자별 shipment를 만드는지, 처리하지 못한 메시지를 DLT로 옮기는지 확인 (#644)
// - 주문 코드는 쓰지 않고, 주문이 보낼 JSON(#644 메시지 약속)을 테스트가 토픽에 직접 넣는다
// - 리스너는 별도 스레드에서 받으므로 결과는 await()로 기다린 뒤 DB로 확인한다
// - 테스트 브로커는 파티션이 하나라 보낸 순서대로 처리된다. 뒤 메시지가 처리됐으면 앞 메시지도 처리가 끝난 것이다
class DeliveryOrderPaidKafkaIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    // 이 클래스 전용 번호대. 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 주문 번호를 쓴다
    private static final AtomicLong SEQ = new AtomicLong(9_645_000L);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    ProducerFactory<String, Object> producerFactory;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    private DeliveryTopicReader dltReader;

    @BeforeEach
    void setUp() {
        dltReader = new DeliveryTopicReader(kafkaConnectionDetails.getBootstrapServers(), DeliveryInboundTopics.ORDER_PAID_DLT);
    }

    @AfterEach
    void tearDown() {
        dltReader.close();
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 결제_완료를_Kafka로_받으면_판매자별_shipment를_만든다() {
        // given
        long orderId = SEQ.incrementAndGet();

        // when
        sendOrderPaid(orderId, orderPaidMessage(orderId));

        // then
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countShipments(orderId)).isEqualTo(2));
        assertThat(sellerIdsOf(orderId)).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void 같은_결제_완료를_두_번_받아도_판매자별_shipment는_한_건이다() {
        // given: 처음 받은 결제 완료가 처리될 때까지 기다림
        long orderId = SEQ.incrementAndGet();
        sendOrderPaid(orderId, orderPaidMessage(orderId));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countShipments(orderId)).isEqualTo(2));

        // when: 같은 메시지를 다시 보내고, 뒤이어 다른 주문을 보냄
        sendOrderPaid(orderId, orderPaidMessage(orderId));
        long laterOrderId = SEQ.incrementAndGet();
        sendOrderPaid(laterOrderId, orderPaidMessage(laterOrderId));

        // then: 뒤 주문이 처리됐으면 다시 보낸 메시지도 처리가 끝난 것이다
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countShipments(laterOrderId)).isEqualTo(2));
        assertThat(countShipments(orderId)).isEqualTo(2);
    }

    @Test
    void 무료배송_기준이_없는_판매자의_결제_완료도_처리해_부과_배송비를_저장한다() {
        // given: 상품 금액이 커도 무료배송 기준이 없으면 기본 배송비를 낸다
        long orderId = SEQ.incrementAndGet();
        Map<String, Object> message = orderPaidMessage(orderId,
                List.of(sellerOrder(10L, orderId * 10 + 1, null, null, 10_000_000L)));

        // when
        sendOrderPaid(orderId, message);

        // then
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countShipments(orderId)).isEqualTo(1));
        Integer chargedShippingFee = jdbcTemplate.queryForObject(
                "SELECT charged_shipping_fee FROM shipment WHERE order_id = ?", Integer.class, orderId);
        assertThat(chargedShippingFee).isEqualTo(3_000);
    }

    @Test
    void 주문_번호가_빠진_메시지는_다시_시도하지_않고_DLT로_옮기고_다음_메시지를_처리한다() {
        // given: 리스너가 파티션을 맡았는지 정상 메시지로 먼저 확인한다. 그래야 아래 시간 측정에 그룹 배정 대기가 섞이지 않는다
        awaitListenerReady();
        long badOrderId = SEQ.incrementAndGet();
        Map<String, Object> invalid = new HashMap<>(orderPaidMessage(badOrderId));
        invalid.remove("orderId");
        long goodOrderId = SEQ.incrementAndGet();

        // when: 같은 파티션에 잘못된 메시지 뒤에 정상 메시지를 보냄
        long startedAt = System.nanoTime();
        sendOrderPaid(badOrderId, invalid);
        sendOrderPaid(goodOrderId, orderPaidMessage(goodOrderId));

        // then: 다시 시도했다면 1·2·4초를 기다린 뒤에야 DLT에 닿는다. 그보다 훨씬 빨리 닿아야 다시 시도하지 않은 것이다
        List<String> dlt = dltReader.awaitValuesFor(String.valueOf(badOrderId), TIMEOUT);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(dlt).hasSize(1);
        // JSON으로는 읽혔으므로 DLT에는 받는 DTO 모양으로 다시 쓴 값이 간다. 빠진 주문 번호는 null로 남는다
        assertThat(dlt.getFirst()).contains("\"orderId\":null", "\"buyerId\":1");
        assertThat(elapsedMillis).isLessThan(5_000L);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countShipments(goodOrderId)).isEqualTo(2));
        assertThat(countShipments(badOrderId)).isZero();
    }

    @Test
    void JSON으로_읽지_못한_메시지는_원본_그대로_DLT로_옮기고_다음_메시지를_처리한다() {
        // given
        long badOrderId = SEQ.incrementAndGet();
        long goodOrderId = SEQ.incrementAndGet();
        String malformed = "{\"orderId\":" + badOrderId + ", 닫히지 않은 JSON";

        // when
        stringKafkaTemplate().send(DeliveryInboundTopics.ORDER_PAID, String.valueOf(badOrderId), malformed);
        sendOrderPaid(goodOrderId, orderPaidMessage(goodOrderId));

        // then: 받은 바이트를 그대로 옮겨야 사람이 원인을 보고 고쳐 다시 넣을 수 있다
        assertThat(dltReader.awaitValuesFor(String.valueOf(badOrderId), TIMEOUT)).containsExactly(malformed);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countShipments(goodOrderId)).isEqualTo(2));
    }

    private void awaitListenerReady() {
        long orderId = SEQ.incrementAndGet();
        sendOrderPaid(orderId, orderPaidMessage(orderId));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countShipments(orderId)).isEqualTo(2));
    }

    // 주문이 보낼 JSON (#644 메시지 약속). 시각은 오프셋 없는 ISO-8601, 금액은 원
    private static Map<String, Object> orderPaidMessage(long orderId) {
        return orderPaidMessage(orderId, List.of(
                sellerOrder(10L, orderId * 10 + 1, "옵션", 50_000L, 10_000L),
                sellerOrder(20L, orderId * 10 + 2, null, 50_000L, 10_000L)));
    }

    private static Map<String, Object> orderPaidMessage(long orderId, List<Map<String, Object>> sellerOrders) {
        Map<String, Object> address = new HashMap<>();
        address.put("recipientName", "홍길동");
        address.put("recipientPhone", "010-1234-5678");
        address.put("postalCode", "06236");
        address.put("address", "서울시 강남구 테헤란로 1");
        address.put("addressDetail", "101동 202호");
        address.put("deliveryNote", null);
        return Map.of(
                "orderId", orderId,
                "buyerId", 1L,
                "paidAt", "2026-10-19T10:00:00",
                "shippingAddress", address,
                "sellerOrders", sellerOrders);
    }

    private static Map<String, Object> sellerOrder(long sellerId, long orderItemId, String optionName,
                                                   Long freeShippingThreshold, long unitPrice) {
        Map<String, Object> item = new HashMap<>();
        item.put("orderItemId", orderItemId);
        item.put("productName", "상품");
        item.put("optionName", optionName);
        item.put("quantity", 1);
        item.put("unitPrice", unitPrice);
        // 무료배송 기준은 null일 수 있어 null을 받지 않는 Map.of 대신 HashMap을 쓴다
        Map<String, Object> sellerOrder = new HashMap<>();
        sellerOrder.put("sellerId", sellerId);
        sellerOrder.put("baseShippingFee", 3_000L);
        sellerOrder.put("freeShippingThreshold", freeShippingThreshold);
        sellerOrder.put("regionSurcharge", 0L);
        sellerOrder.put("items", List.of(item));
        return sellerOrder;
    }

    private void sendOrderPaid(long orderId, Map<String, Object> message) {
        kafkaTemplate.send(DeliveryInboundTopics.ORDER_PAID, String.valueOf(orderId), message);
    }

    // 앱의 발행 설정(접속 주소)은 그대로 쓰고 값만 JSON 변환 없이 문자열로 보낸다
    private KafkaTemplate<String, String> stringKafkaTemplate() {
        Map<String, Object> config = new HashMap<>(producerFactory.getConfigurationProperties());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer()));
    }

    private long countShipments(long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shipment WHERE order_id = ?", Long.class, orderId);
    }

    private List<Long> sellerIdsOf(long orderId) {
        return jdbcTemplate.queryForList("SELECT seller_id FROM shipment WHERE order_id = ?", Long.class, orderId);
    }
}
