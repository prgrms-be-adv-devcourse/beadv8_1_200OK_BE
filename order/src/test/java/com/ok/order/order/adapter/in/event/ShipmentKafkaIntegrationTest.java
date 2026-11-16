package com.ok.order.order.adapter.in.event;

import com.ok.order.OrderSchema;
import com.ok.order.order.application.service.OrderService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.OrderKafkaTopicReader;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 배송과 Kafka로 주고받는 경로(#603)를 실제 DB·Kafka(Testcontainers)로 확인한다.
// - 보내기: 결제 완료가 order.order-paid.v1로 나간다
// - 받기: 테스트가 배송 대신 배송 시작·완료 메시지를 보낸다. 메시지는 배송처럼 타입 헤더 없는 JSON 객체로 보낸다
class ShipmentKafkaIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_603_001L;
    private static final Long SELLER_ID = 9_603_901L;
    private static final String ORDER_PAID_TOPIC = "order.order-paid.v1";
    // 다시 보낸 중복까지 세려고 이 시간 동안 계속 읽는다. 커밋 직후 보내므로 정상이면 바로 읽힌다
    private static final Duration READ_DURATION = Duration.ofSeconds(3);
    // 다시 시도(1·2·4초)를 다 쓰고 기록될 때까지 넉넉히 기다린다
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    OrderService orderService;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private OrderKafkaTopicReader orderPaidReader;
    private String missingOrderKey;

    @BeforeEach
    void setUp() {
        orderPaidReader = new OrderKafkaTopicReader(kafkaConnectionDetails.getBootstrapServers(), ORDER_PAID_TOPIC);
        missingOrderKey = "missing-" + UUID.randomUUID();
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문, outbox 줄, 실패 기록을 지운다
    @AfterEach
    void tearDown() {
        orderPaidReader.close();
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_message_failure WHERE message_key = ?",
                missingOrderKey);
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_outbox WHERE message_key IN ("
                + "SELECT CAST(id AS CHAR) FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?)", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
    }

    @Test
    void 결제_완료하면_배송이_받을_메시지가_주문_ID를_키로_한_건_나간다() {
        // given
        Long orderId = pendingOrder();

        // when
        orderService.completePayment(orderId);

        // then
        List<String> messages = orderPaidReader.readValuesFor(String.valueOf(orderId), READ_DURATION);
        assertThat(messages).hasSize(1);
        JsonNode message = jsonMapper.readTree(messages.get(0));
        assertSoftly(softly -> {
            softly.assertThat(message.get("orderId").asLong()).isEqualTo(orderId);
            softly.assertThat(message.get("shippingAddress").get("postalCode").asString()).isEqualTo("06236");
            softly.assertThat(message.get("sellerOrders").get(0).get("sellerId").asLong()).isEqualTo(SELLER_ID);
            softly.assertThat(message.get("sellerOrders").get(0).get("items").size()).isEqualTo(1);
        });
    }

    @Test
    void 배송_시작과_완료를_Kafka로_받으면_주문_상품이_배송_중을_거쳐_배송_완료가_된다() {
        // given
        Long orderId = paidOrder();
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 11, 16, 15, 0);

        // when
        send(DeliveryTopics.SHIPMENT_DISPATCHED, String.valueOf(orderId), dispatched(orderId));
        boolean shipping = waitFor(() -> itemStatuses(orderId).equals(List.of("SHIPPING")));
        send(DeliveryTopics.SHIPMENT_DELIVERED, String.valueOf(orderId), delivered(orderId, "2026-11-16T15:00:00"));

        // then
        assertThat(shipping).isTrue();
        assertThat(waitFor(() -> itemStatuses(orderId).equals(List.of("DELIVERED")))).isTrue();
        assertThat(deliveredAt(orderId)).isEqualTo(deliveredAt);
    }

    @Test
    void 배송_완료가_배송_시작보다_먼저_와도_배송_완료로_남는다() {
        // given
        Long orderId = paidOrder();
        send(DeliveryTopics.SHIPMENT_DELIVERED, String.valueOf(orderId), delivered(orderId, "2026-11-16T15:00:00"));
        assertThat(waitFor(() -> itemStatuses(orderId).equals(List.of("DELIVERED")))).isTrue();

        // when: 늦게 온 배송 시작과, 다시 온 배송 완료
        RecordMetadata lateDispatched = send(DeliveryTopics.SHIPMENT_DISPATCHED, String.valueOf(orderId), dispatched(orderId));
        RecordMetadata duplicated = send(DeliveryTopics.SHIPMENT_DELIVERED, String.valueOf(orderId),
                delivered(orderId, "2026-11-17T09:00:00"));

        // then: 두 메시지를 처리하고 오프셋을 넘겼지만 상태와 배송 완료 시각은 처음 그대로다
        assertThat(waitFor(() -> consumedPast(lateDispatched) && consumedPast(duplicated))).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(itemStatuses(orderId)).containsExactly("DELIVERED");
            softly.assertThat(deliveredAt(orderId)).isEqualTo(LocalDateTime.of(2026, 11, 16, 15, 0));
        });
    }

    @Test
    void 주문_ID가_없는_배송_메시지는_다시_시도하지_않고_실패_기록에_남긴다() {
        // when
        send(DeliveryTopics.SHIPMENT_DISPATCHED, missingOrderKey, Map.of("shipmentId", 1L, "sellerId", SELLER_ID));

        // then
        assertThat(waitFor(() -> failureOf(missingOrderKey) != null)).isTrue();
        Map<String, Object> sut = failureOf(missingOrderKey);
        assertSoftly(softly -> {
            softly.assertThat(sut.get("consumer_group")).isEqualTo(DeliveryTopics.SHIPPING_STATUS_GROUP);
            softly.assertThat(sut.get("topic")).isEqualTo(DeliveryTopics.SHIPMENT_DISPATCHED);
            softly.assertThat((String) sut.get("failure_reason")).startsWith("IllegalArgumentException");
        });
    }

    private Map<String, Object> dispatched(Long orderId) {
        return Map.of("shipmentId", 1L, "orderId", orderId, "sellerId", SELLER_ID, "carrier", "CJ",
                "trackingNumber", "1234567890", "shippedAt", "2026-11-16T10:00:00");
    }

    private Map<String, Object> delivered(Long orderId, String deliveredAt) {
        return Map.of("shipmentId", 1L, "orderId", orderId, "sellerId", SELLER_ID, "deliveredAt", deliveredAt);
    }

    private RecordMetadata send(String topic, String key, Map<String, Object> message) {
        try {
            return kafkaTemplate.send(topic, key, message).get().getRecordMetadata();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 메시지를 보내지 못했습니다. topic=" + topic, e);
        }
    }

    // 배송 상태 그룹이 이 메시지 다음 위치까지 오프셋을 커밋했는지. 커밋은 리스너 처리가 끝난 뒤에 한다
    private boolean consumedPast(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(DeliveryTopics.SHIPPING_STATUS_GROUP)
                    .partitionsToOffsetAndMetadata().get().get(partition);
            return committed != null && committed.offset() > sent.offset();
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> itemStatuses(Long orderId) {
        return jdbcTemplate.queryForList("SELECT oi.status FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id = ?",
                String.class, orderId);
    }

    private LocalDateTime deliveredAt(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT oi.delivered_at FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id = ?",
                LocalDateTime.class, orderId);
    }

    private Map<String, Object> failureOf(String key) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT consumer_group, topic, failure_reason FROM "
                + OrderSchema.NAME + ".order_message_failure WHERE message_key = ?", key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean waitFor(Supplier<Boolean> condition) {
        Instant until = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(until)) {
            if (condition.get()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private Long paidOrder() {
        Long orderId = pendingOrder();
        orderService.completePayment(orderId);
        return orderId;
    }

    private Long pendingOrder() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderLine(9_603_501L, 9_603_601L, SELLER_ID, "감자", "1kg", 10_000L, 1)));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, null, 3_000L, 0L));
        order.assignIdempotencyKey("key-603-" + System.nanoTime());
        order.registerShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", null);
        order.agreeThirdPartyProvision(true);
        return orderService.create(order);
    }
}
