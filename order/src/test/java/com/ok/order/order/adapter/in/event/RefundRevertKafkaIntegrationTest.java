package com.ok.order.order.adapter.in.event;

import com.ok.order.OrderSchema;
import com.ok.order.order.application.dto.RecordOrderItemRefundCommand;
import com.ok.order.order.application.service.OrderService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.OrderKafkaTopicReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 환불과 Kafka로 주고받는 취소 기록 되돌리기(#604)를 실제 DB·Kafka(Testcontainers)로 확인한다.
// - 받기: 테스트가 환불 대신 되돌리기 요청을 보낸다. 환불처럼 타입 헤더 없는 JSON 객체로 보낸다
// - 보내기: 결과가 order.order-refund-reverted.v1 / order.order-refund-revert-failed.v1로 나간다
class RefundRevertKafkaIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_604_001L;
    private static final Long SELLER_ID = 9_604_901L;
    private static final String REVERTED_TOPIC = "order.order-refund-reverted.v1";
    private static final String REVERT_FAILED_TOPIC = "order.order-refund-revert-failed.v1";
    // 다시 보낸 중복까지 세려고 이 시간 동안 계속 읽는다. 커밋 직후 보내므로 정상이면 바로 읽힌다
    private static final Duration READ_DURATION = Duration.ofSeconds(3);
    // 다시 시도(1·2·4초)를 다 쓰고 기록될 때까지 넉넉히 기다린다
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    OrderService orderService;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private OrderKafkaTopicReader revertedReader;
    private OrderKafkaTopicReader revertFailedReader;
    private String missingIdKey;

    @BeforeEach
    void setUp() {
        revertedReader = new OrderKafkaTopicReader(kafkaConnectionDetails.getBootstrapServers(), REVERTED_TOPIC);
        revertFailedReader = new OrderKafkaTopicReader(kafkaConnectionDetails.getBootstrapServers(), REVERT_FAILED_TOPIC);
        missingIdKey = "missing-" + UUID.randomUUID();
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문, 환불 기록, outbox 줄, 실패 기록을 지운다
    @AfterEach
    void tearDown() {
        revertedReader.close();
        revertFailedReader.close();
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_message_failure WHERE message_key = ?", missingIdKey);
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        String items = "SELECT oi.id FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_outbox WHERE message_key IN ("
                + "SELECT CAST(id AS CHAR) FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?)", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_item_refund WHERE order_item_id IN ("
                + "SELECT id FROM (" + items + ") t)", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
    }

    @Test
    void 되돌리기_요청을_받으면_취소_기록을_되돌리고_환불이_받을_되돌림을_한_건_보낸다() {
        // given
        Long orderId = paidOrder(1);
        Long itemId = itemIds(orderId).get(0);
        Long refundId = 9_604_101L;
        orderService.recordRefund(new RecordOrderItemRefundCommand(itemId, refundId, 1, 0L));

        // when
        send(String.valueOf(orderId), revertRequest(refundId, orderId, itemId, 1));

        // then
        assertThat(waitFor(() -> revertedAt(refundId) != null)).isTrue();
        List<String> messages = revertedReader.readValuesFor(String.valueOf(orderId), READ_DURATION);
        assertThat(messages).hasSize(1);
        JsonNode message = jsonMapper.readTree(messages.get(0));
        assertSoftly(softly -> {
            softly.assertThat(message.get("refundId").asLong()).isEqualTo(refundId);
            softly.assertThat(message.get("orderId").asLong()).isEqualTo(orderId);
            softly.assertThat(message.get("orderItemId").asLong()).isEqualTo(itemId);
        });
    }

    @Test
    void 같은_요청이_두_번_오면_한_번만_되돌리고_두_번_다_되돌림으로_답한다() {
        // given
        Long orderId = paidOrder(1);
        Long itemId = itemIds(orderId).get(0);
        Long refundId = 9_604_102L;
        orderService.recordRefund(new RecordOrderItemRefundCommand(itemId, refundId, 1, 0L));
        send(String.valueOf(orderId), revertRequest(refundId, orderId, itemId, 1));
        assertThat(waitFor(() -> revertedAt(refundId) != null)).isTrue();
        Object firstRevertedAt = revertedAt(refundId);

        // when
        send(String.valueOf(orderId), revertRequest(refundId, orderId, itemId, 1));

        // then: 처음 되돌린 시각이 그대로이고, 답은 요청마다 하나씩 나간다
        assertThat(waitFor(() -> revertedReader.readValuesFor(String.valueOf(orderId), READ_DURATION).size() == 2)).isTrue();
        assertThat(revertedAt(refundId)).isEqualTo(firstRevertedAt);
    }

    @Test
    void 같은_묶음이_출고된_뒤_온_되돌리기는_되돌리지_않고_출고됨으로_답한다() {
        // given: 감자(모두 환불)와 사과가 같은 판매자 묶음이고, 사과가 출고됨
        Long orderId = paidOrder(2);
        List<Long> itemIds = itemIds(orderId);
        Long potatoId = itemIds.get(0);
        Long refundId = 9_604_103L;
        orderService.recordRefund(new RecordOrderItemRefundCommand(potatoId, refundId, 1, 0L));
        orderService.startShipping(orderId, SELLER_ID);

        // when
        send(String.valueOf(orderId), revertRequest(refundId, orderId, potatoId, 1));

        // then
        List<String> messages = revertFailedReader.readValuesFor(String.valueOf(orderId), Duration.ofSeconds(10));
        assertThat(messages).hasSize(1);
        JsonNode message = jsonMapper.readTree(messages.get(0));
        assertSoftly(softly -> {
            softly.assertThat(message.get("refundId").asLong()).isEqualTo(refundId);
            softly.assertThat(message.get("reason").asString()).isEqualTo("ALREADY_SHIPPED");
            softly.assertThat(revertedAt(refundId)).isNull();
        });
    }

    @Test
    void ID가_빠진_되돌리기_요청은_다시_시도하지_않고_실패_기록에_남긴다() {
        // when
        send(missingIdKey, Map.of("refundId", 1L, "orderItemId", 2L, "quantity", 1));

        // then
        assertThat(waitFor(() -> failureOf(missingIdKey) != null)).isTrue();
        Map<String, Object> sut = failureOf(missingIdKey);
        assertSoftly(softly -> {
            softly.assertThat(sut.get("consumer_group")).isEqualTo(RefundTopics.REFUND_REVERT_GROUP);
            softly.assertThat(sut.get("topic")).isEqualTo(RefundTopics.REFUND_ORDER_REVERT_REQUESTED);
            softly.assertThat((String) sut.get("failure_reason")).startsWith("IllegalArgumentException");
        });
    }

    private Map<String, Object> revertRequest(Long refundId, Long orderId, Long orderItemId, int quantity) {
        return Map.of("refundId", refundId, "orderId", orderId, "orderItemId", orderItemId, "quantity", quantity);
    }

    private void send(String key, Map<String, Object> message) {
        try {
            kafkaTemplate.send(RefundTopics.REFUND_ORDER_REVERT_REQUESTED, key, message).get();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 메시지를 보내지 못했습니다. key=" + key, e);
        }
    }

    private Object revertedAt(Long refundId) {
        List<Object> rows = jdbcTemplate.queryForList("SELECT reverted_at FROM " + OrderSchema.NAME
                + ".order_item_refund WHERE refund_id = ?", Object.class, refundId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Long> itemIds(Long orderId) {
        return jdbcTemplate.queryForList("SELECT oi.id FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id = ? ORDER BY oi.id",
                Long.class, orderId);
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

    // 결제 완료된 주문. 같은 판매자의 감자(1개)부터 lineCount개 상품을 담는다
    private Long paidOrder(int lineCount) {
        List<OrderLine> lines = List.of(
                new OrderLine(9_604_501L, 9_604_601L, SELLER_ID, "감자", "1kg", 10_000L, 1),
                new OrderLine(9_604_502L, 9_604_602L, SELLER_ID, "사과", "1kg", 5_000L, 1));
        Order order = Order.create(BUYER_ID, lines.subList(0, lineCount));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, null, 3_000L, 0L));
        order.assignIdempotencyKey("key-604-" + System.nanoTime());
        order.registerShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", null);
        order.agreeThirdPartyProvision(true);
        Long orderId = orderService.create(order);
        orderService.completePayment(orderId);
        return orderId;
    }
}
