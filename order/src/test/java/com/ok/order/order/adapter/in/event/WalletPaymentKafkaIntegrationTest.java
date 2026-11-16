package com.ok.order.order.adapter.in.event;

import com.ok.order.OrderSchema;
import com.ok.order.cart.application.service.CartService;
import com.ok.order.order.application.service.OrderService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeProductPort;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 지갑의 결제 결과를 Kafka로 받는 경로(#602)를 실제 Kafka(Testcontainers)로 확인한다. 테스트가 지갑 대신 결과 메시지를 보낸다.
// 메시지는 지갑처럼 타입 헤더 없는 JSON 객체로 보낸다(Map은 JSON 객체로 직렬화된다)
class WalletPaymentKafkaIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_602_001L;
    private static final Long SELLER_ID = 9_602_901L;
    private static final Long OPTION_ID = 9_602_601L;
    // 장바구니에 같이 담겨 있지만 주문하지 않은 옵션. 결제 완료 뒤에도 장바구니에 남아야 한다
    private static final Long NOT_ORDERED_OPTION_ID = 9_602_602L;
    // 다시 시도(1·2·4초)를 다 쓰고 기록될 때까지 넉넉히 기다린다
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
    // 배송이 받는 결제 완료 메시지 토픽(#603). 결제 완료와 같은 트랜잭션에서 outbox에 남는다
    private static final String ORDER_PAID_TOPIC = "order.order-paid.v1";

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    OrderService orderService;

    @Autowired
    FakeProductPort fakeProductPort;

    @Autowired
    CartService cartService;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String missingOrderKey;

    @BeforeEach
    void setUp() {
        fakeProductPort.clear();
        missingOrderKey = "missing-" + UUID.randomUUID();
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문·장바구니와 실패 기록을 지운다
    @AfterEach
    void tearDown() {
        fakeProductPort.clear();
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_message_failure WHERE message_key = ?",
                missingOrderKey);
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_outbox WHERE topic = ? AND message_key IN ("
                + "SELECT CAST(id AS CHAR) FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?)", ORDER_PAID_TOPIC, BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
        jdbcTemplate.update("DELETE ci FROM " + OrderSchema.NAME + ".cart_item ci JOIN " + OrderSchema.NAME
                + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", BUYER_ID);
    }

    @Test
    void 결제_완료를_Kafka로_받으면_주문이_결제_완료되고_주문한_옵션만_장바구니에서_빠지고_배송용_메시지가_남는다() {
        // given
        cartService.addItem(BUYER_ID, OPTION_ID, 1);
        cartService.addItem(BUYER_ID, NOT_ORDERED_OPTION_ID, 1);
        Long orderId = savedOrder();

        // when
        send(WalletTopics.WALLET_PAYMENT_COMPLETED, String.valueOf(orderId), completed(orderId));

        // then: 장바구니 정리와 배송용 메시지는 결제 완료와 한 트랜잭션이라 PAID가 보이면 함께 끝나 있다
        assertThat(waitFor(() -> "PAID".equals(statusOf(orderId)))).isTrue();
        List<String> paidMessages = orderPaidMessages(orderId);
        assertThat(paidMessages).hasSize(1);
        JsonNode message = jsonMapper.readTree(paidMessages.getFirst());
        JsonNode sellerOrder = message.get("sellerOrders").get(0);
        JsonNode item = sellerOrder.get("items").get(0);
        assertSoftly(softly -> {
            softly.assertThat(itemStatuses(orderId)).containsOnly("PREPARING");
            softly.assertThat(cartOptionIds()).containsExactly(NOT_ORDERED_OPTION_ID);
            softly.assertThat(message.get("orderId").asLong()).isEqualTo(orderId);
            softly.assertThat(message.get("buyerId").asLong()).isEqualTo(BUYER_ID);
            softly.assertThat(message.get("shippingAddress").get("postalCode").asString()).isEqualTo("06236");
            softly.assertThat(message.get("sellerOrders").size()).isEqualTo(1);
            softly.assertThat(sellerOrder.get("sellerId").asLong()).isEqualTo(SELLER_ID);
            softly.assertThat(sellerOrder.get("baseShippingFee").asLong()).isEqualTo(3_000L);
            softly.assertThat(item.get("productName").asString()).isEqualTo("감자");
            softly.assertThat(item.get("quantity").asInt()).isEqualTo(1);
        });
    }

    @Test
    void 결제_실패를_Kafka로_받으면_결제_실패로_바뀌고_재고를_풀고_장바구니는_그대로다() {
        // given
        cartService.addItem(BUYER_ID, OPTION_ID, 1);
        Long orderId = savedOrder();

        // when
        send(WalletTopics.WALLET_PAYMENT_FAILED, String.valueOf(orderId),
                Map.of("orderId", orderId, "buyerId", BUYER_ID, "reason", "INSUFFICIENT_BALANCE"));

        // then: 재고 해제는 결제 실패를 커밋한 뒤 부른다(#657)
        assertThat(waitFor(() -> fakeProductPort.releasedOrderIds().contains(orderId))).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(statusOf(orderId)).isEqualTo("PAYMENT_FAILED");
            softly.assertThat(itemStatuses(orderId)).containsOnly("PAYMENT_FAILED");
            softly.assertThat(stockReleasedAt(orderId)).isNotNull();
            softly.assertThat(cartOptionIds()).containsExactly(OPTION_ID);
        });
    }

    // 결제 실패와 재고 해제 HTTP가 한 트랜잭션에 묶이면 해제 응답을 기다리는 동안 주문 행 잠금과 커넥션을 쥔다.
    // 해제를 받는 순간 다른 연결로 주문 상태를 읽어, 결제 실패가 이미 커밋된 뒤 해제를 부르는지 본다(#657)
    @Test
    void 결제_실패는_재고_해제를_부르기_전에_커밋된다() {
        // given
        Long orderId = savedOrder();
        AtomicReference<String> statusAtRelease = new AtomicReference<>();
        fakeProductPort.beforeRelease(released -> statusAtRelease.set(statusOf(released)));

        // when
        send(WalletTopics.WALLET_PAYMENT_FAILED, String.valueOf(orderId), failed(orderId));

        // then
        assertThat(waitFor(() -> fakeProductPort.releasedOrderIds().contains(orderId))).isTrue();
        assertThat(statusAtRelease.get()).isEqualTo("PAYMENT_FAILED");
    }

    // 결제 실패를 먼저 커밋하므로 재고 해제가 실패해도 결제 실패는 남고, 해제는 정리 스케줄러가 다시 한다(#657)
    @Test
    void 결제_실패_처리_중_재고_해제가_실패해도_결제_실패는_남고_해제는_재처리_대상이_된다() {
        // given
        Long orderId = savedOrder();
        fakeProductPort.failReleaseWith(OrderErrorCode.PRODUCT_API_FAILED);

        // when
        RecordMetadata sent = send(WalletTopics.WALLET_PAYMENT_FAILED, String.valueOf(orderId), failed(orderId));

        // then
        assertThat(waitFor(() -> consumedPast(sent))).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(statusOf(orderId)).isEqualTo("PAYMENT_FAILED");
            softly.assertThat(stockReleasedAt(orderId)).isNull();
            softly.assertThat(fakeProductPort.releasedOrderIds()).doesNotContain(orderId);
        });
    }

    @Test
    void 주문_ID가_없는_결과는_다시_시도하지_않고_실패_기록에_남긴다() {
        // given
        Map<String, Object> message = new HashMap<>();
        message.put("buyerId", BUYER_ID);
        message.put("amount", 10_000L);
        message.put("paidAt", "2026-11-16T10:00:00");

        // when
        send(WalletTopics.WALLET_PAYMENT_COMPLETED, missingOrderKey, message);

        // then
        assertThat(waitFor(() -> failureOf(missingOrderKey) != null)).isTrue();
        Map<String, Object> sut = failureOf(missingOrderKey);
        assertSoftly(softly -> {
            softly.assertThat(sut.get("consumer_group")).isEqualTo(WalletTopics.PAYMENT_RESULT_GROUP);
            softly.assertThat(sut.get("topic")).isEqualTo(WalletTopics.WALLET_PAYMENT_COMPLETED);
            softly.assertThat((String) sut.get("failure_reason")).startsWith("IllegalArgumentException");
        });
    }

    // 지갑은 결제 만료 요청의 답이나 재전달로 같은 결과를 다시 보낼 수 있다.
    // 두 번이 동시에 오는 경우는 이 테스트 범위 밖이다(같은 키라 한 파티션에서 차례로 처리된다)
    @Test
    void 같은_결제_완료를_Kafka로_두_번_받아도_한_번만_반영한다() {
        // given
        Long orderId = savedOrder();
        send(WalletTopics.WALLET_PAYMENT_COMPLETED, String.valueOf(orderId), completed(orderId));
        assertThat(waitFor(() -> "PAID".equals(statusOf(orderId)))).isTrue();
        LocalDateTime paidUpdatedAt = updatedAtOf(orderId);

        // when
        RecordMetadata sent = send(WalletTopics.WALLET_PAYMENT_COMPLETED, String.valueOf(orderId), completed(orderId));

        // then: 두 번째 결과를 처리하고 오프셋까지 넘겼지만 주문은 다시 바뀌지 않았고 배송용 메시지도 하나다
        assertThat(waitFor(() -> consumedPast(sent))).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(statusOf(orderId)).isEqualTo("PAID");
            softly.assertThat(updatedAtOf(orderId)).isEqualTo(paidUpdatedAt);
            softly.assertThat(orderPaidMessages(orderId)).hasSize(1);
        });
    }

    private Map<String, Object> completed(Long orderId) {
        return Map.of("orderId", orderId, "buyerId", BUYER_ID, "amount", 23_000L, "paidAt", "2026-11-16T10:00:00");
    }

    private Map<String, Object> failed(Long orderId) {
        return Map.of("orderId", orderId, "buyerId", BUYER_ID, "reason", "INSUFFICIENT_BALANCE");
    }

    private RecordMetadata send(String topic, String key, Map<String, Object> message) {
        try {
            return kafkaTemplate.send(topic, key, message).get().getRecordMetadata();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 메시지를 보내지 못했습니다. topic=" + topic, e);
        }
    }

    // 주문 결제 결과 그룹이 이 메시지 다음 위치까지 오프셋을 커밋했는지. 커밋은 리스너 처리가 끝난 뒤에 한다
    private boolean consumedPast(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(WalletTopics.PAYMENT_RESULT_GROUP)
                    .partitionsToOffsetAndMetadata().get().get(partition);
            return committed != null && committed.offset() > sent.offset();
        } catch (Exception e) {
            return false;
        }
    }

    private String statusOf(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM " + OrderSchema.NAME + ".orders WHERE id = ?",
                String.class, orderId);
    }

    private List<String> itemStatuses(Long orderId) {
        return jdbcTemplate.queryForList("SELECT oi.status FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id = ?",
                String.class, orderId);
    }

    private List<Long> cartOptionIds() {
        return jdbcTemplate.queryForList("SELECT ci.product_option_id FROM " + OrderSchema.NAME + ".cart_item ci JOIN "
                + OrderSchema.NAME + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", Long.class, BUYER_ID);
    }

    // 이 주문의 결제 완료 메시지 본문(JSON). outbox 줄은 보낸 뒤에도 지우지 않아 남은 줄 수가 남긴 메시지 수다
    private List<String> orderPaidMessages(Long orderId) {
        return jdbcTemplate.queryForList("SELECT payload FROM " + OrderSchema.NAME
                + ".order_outbox WHERE topic = ? AND message_key = ?", String.class, ORDER_PAID_TOPIC, String.valueOf(orderId));
    }

    private LocalDateTime updatedAtOf(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM " + OrderSchema.NAME + ".orders WHERE id = ?",
                LocalDateTime.class, orderId);
    }

    private LocalDateTime stockReleasedAt(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT stock_released_at FROM " + OrderSchema.NAME + ".orders WHERE id = ?",
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

    private Long savedOrder() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderLine(9_602_501L, OPTION_ID, SELLER_ID, "감자", "1kg", 20_000L, 1)));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, null, 3_000L, 0L));
        order.assignIdempotencyKey("key-602-" + System.nanoTime());
        order.registerShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", null);
        order.agreeThirdPartyProvision(true);
        return orderService.create(order);
    }
}
