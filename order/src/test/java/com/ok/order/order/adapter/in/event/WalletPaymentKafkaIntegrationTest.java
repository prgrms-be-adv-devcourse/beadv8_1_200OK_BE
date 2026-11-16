package com.ok.order.order.adapter.in.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.order.OrderSchema;
import com.ok.order.order.application.service.OrderService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    // 다시 시도(1·2·4초)를 다 쓰고 기록될 때까지 넉넉히 기다린다
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    OrderService orderService;

    @Autowired
    FakeProductPort fakeProductPort;

    @Autowired
    ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String missingOrderKey;

    @BeforeEach
    void setUp() {
        fakeProductPort.clear();
        missingOrderKey = "missing-" + UUID.randomUUID();
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문과 실패 기록을 지운다
    @AfterEach
    void tearDown() {
        fakeProductPort.clear();
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_message_failure WHERE message_key = ?",
                missingOrderKey);
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
    }

    @Test
    void 결제_완료를_Kafka로_받으면_주문이_결제_완료된다() {
        // given
        Long orderId = savedOrder();

        // when
        send(WalletTopics.WALLET_PAYMENT_COMPLETED, String.valueOf(orderId), completed(orderId));

        // then
        assertThat(waitFor(() -> "PAID".equals(statusOf(orderId)))).isTrue();
    }

    @Test
    void 결제_실패를_Kafka로_받으면_결제_실패로_바뀌고_재고를_푼다() {
        // given
        Long orderId = savedOrder();

        // when
        send(WalletTopics.WALLET_PAYMENT_FAILED, String.valueOf(orderId),
                Map.of("orderId", orderId, "buyerId", BUYER_ID, "reason", "INSUFFICIENT_BALANCE"));

        // then: 재고 해제는 결제 실패를 커밋한 뒤 부른다(#657)
        assertThat(waitFor(() -> fakeProductPort.releasedOrderIds().contains(orderId))).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(statusOf(orderId)).isEqualTo("PAYMENT_FAILED");
            softly.assertThat(stockReleasedAt(orderId)).isNotNull();
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

    @Test
    void 같은_결제_완료를_Spring과_Kafka로_한_번씩_받아도_한_번만_반영한다() {
        // given: 지갑 전환 기간에는 같은 결과가 Spring 이벤트로 먼저 와서 결제 완료된 상태다
        Long orderId = savedOrder();
        transactionTemplate.executeWithoutResult(status -> applicationEventPublisher.publishEvent(
                new WalletPaymentCompletedEvent(orderId, BUYER_ID, 23_000L, LocalDateTime.of(2026, 11, 16, 10, 0))));
        assertThat(waitFor(() -> "PAID".equals(statusOf(orderId)))).isTrue();
        LocalDateTime paidUpdatedAt = updatedAtOf(orderId);

        // when
        RecordMetadata sent = send(WalletTopics.WALLET_PAYMENT_COMPLETED, String.valueOf(orderId), completed(orderId));

        // then: Kafka 결과를 처리하고 오프셋까지 넘겼지만 주문은 다시 바뀌지 않았다
        assertThat(waitFor(() -> consumedPast(sent))).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(statusOf(orderId)).isEqualTo("PAID");
            softly.assertThat(updatedAtOf(orderId)).isEqualTo(paidUpdatedAt);
        });
    }

    private Map<String, Object> completed(Long orderId) {
        return Map.of("orderId", orderId, "buyerId", BUYER_ID, "amount", 23_000L, "paidAt", "2026-11-16T10:00:00");
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
