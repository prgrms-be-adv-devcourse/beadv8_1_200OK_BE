package com.ok.order.order.application.service;

import com.ok.order.OrderSchema;
import com.ok.order.order.application.port.in.AutoConfirmPurchaseUseCase;
import com.ok.order.order.application.port.in.ConfirmPurchaseUseCase;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 구매확정을 정산에 Kafka로 보내는 경로(#600)를 실제 DB·Kafka(Testcontainers)로 확인한다.
// 정산이 받을 토픽을 키(orderItemId)로 골라 읽어, 다른 테스트의 메시지와 섞이지 않게 한다
class PurchaseConfirmKafkaIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_600_001L;
    private static final Long SELLER_ID = 9_600_901L;
    private static final String TOPIC = "order.order-product-confirmed.v1";
    // 다시 보낸 중복까지 세려고 이 시간 동안 계속 읽는다. 커밋 직후 보내므로 정상이면 바로 읽힌다
    private static final Duration READ_DURATION = Duration.ofSeconds(3);

    @Autowired
    ConfirmPurchaseUseCase confirmPurchase;

    @Autowired
    AutoConfirmPurchaseUseCase autoConfirmPurchase;

    @Autowired
    OrderService orderService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Clock clock;

    private OrderKafkaTopicReader reader;

    @BeforeEach
    void setUp() {
        reader = new OrderKafkaTopicReader(kafkaConnectionDetails.getBootstrapServers(), TOPIC);
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문과 outbox 줄을 지운다
    @AfterEach
    void tearDown() {
        reader.close();
        String items = "SELECT CAST(oi.id AS CHAR) FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id JOIN " + OrderSchema.NAME
                + ".orders o ON g.order_id = o.id WHERE o.buyer_id = ?";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_outbox WHERE topic = ? AND message_key IN ("
                + items + ")", TOPIC, BUYER_ID);
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
    void 구매확정하면_정산이_받을_메시지가_주문_상품_ID를_키로_한_건_나간다() {
        // given
        Long orderId = deliveredOrder(LocalDateTime.now(clock));
        Long orderItemId = orderItemIdOf(orderId);

        // when
        confirmPurchase.confirmPurchase(BUYER_ID, orderId, orderItemId);

        // then: 상품 10,000원 × 2개, 판매자 묶음 배송비 3,000원
        List<String> messages = reader.readValuesFor(String.valueOf(orderItemId), READ_DURATION);
        assertThat(messages).hasSize(1);
        JsonNode message = jsonMapper.readTree(messages.get(0));
        assertSoftly(softly -> {
            softly.assertThat(message.get("orderId").asLong()).isEqualTo(orderId);
            softly.assertThat(message.get("orderItemId").asLong()).isEqualTo(orderItemId);
            softly.assertThat(message.get("sellerId").asLong()).isEqualTo(SELLER_ID);
            softly.assertThat(message.get("totalItemPrice").asLong()).isEqualTo(20_000L);
            softly.assertThat(message.get("sellerShippingPrice").asLong()).isEqualTo(3_000L);
        });
    }

    @Test
    void 구매확정이_롤백되면_메시지도_나가지_않는다() {
        // given
        Long orderId = deliveredOrder(LocalDateTime.now(clock));
        Long orderItemId = orderItemIdOf(orderId);

        // when
        transactionTemplate.executeWithoutResult(status -> {
            confirmPurchase.confirmPurchase(BUYER_ID, orderId, orderItemId);
            status.setRollbackOnly();
        });

        // then
        assertThat(reader.readValuesFor(String.valueOf(orderItemId), READ_DURATION)).isEmpty();
    }

    @Test
    void 배송_완료_뒤_기간이_지나_자동_구매확정돼도_메시지가_나간다() {
        // given: 자동 구매확정 기준(배송 완료 3일)보다 오래전에 배송 완료됐다
        Long orderId = deliveredOrder(LocalDateTime.now(clock).minusDays(4));
        Long orderItemId = orderItemIdOf(orderId);

        // when
        int confirmed = autoConfirmPurchase.autoConfirmPurchases(orderId);

        // then
        assertSoftly(softly -> {
            softly.assertThat(confirmed).isEqualTo(1);
            softly.assertThat(reader.readValuesFor(String.valueOf(orderItemId), READ_DURATION)).hasSize(1);
        });
    }

    // 결제 완료 → 배송 시작 → 배송 완료까지 진행한 주문을 만든다
    private Long deliveredOrder(LocalDateTime deliveredAt) {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderLine(9_600_501L, 9_600_601L, SELLER_ID, "사과", "1kg", 10_000L, 2)));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, null, 3_000L, 0L));
        order.assignIdempotencyKey("key-600-" + System.nanoTime());
        order.registerShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", null);
        order.agreeThirdPartyProvision(true);
        Long orderId = orderService.create(order);
        orderService.completePayment(orderId);
        orderService.startShipping(orderId, SELLER_ID);
        orderService.completeDelivery(orderId, SELLER_ID, deliveredAt);
        return orderId;
    }

    private Long orderItemIdOf(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT oi.id FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id = ?",
                Long.class, orderId);
    }
}
