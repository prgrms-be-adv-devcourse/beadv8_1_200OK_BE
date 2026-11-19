package com.ok.wallet.adapter.in.event;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.service.BuyerWalletService;
import com.ok.wallet.support.KafkaTopicReader;
import com.ok.wallet.support.WalletTestCharger;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 주문이 Kafka로 보낸 주문 생성을 지갑 리스너가 받아 결제하고, 결과를 주문·정산이 받을 토픽에 보내는지 확인한다 (#614)
// - 주문 코드는 쓰지 않고, 주문이 보낼 JSON(#602 약속)을 테스트가 토픽에 직접 넣는다
// - 결과 메시지의 모양(결제 시각 형식 등)은 OrderPaymentServiceIntegrationTest가 본다. 여기서는 받기부터 결과까지 이어지는지 본다
// - 테스트 브로커는 파티션이 하나라 같은 키로 보낸 메시지는 보낸 순서대로 처리된다. 뒤 메시지가 처리됐으면 앞 메시지도 처리가 끝난 것이다
class OrderKafkaListenerIntegrationTest extends AbstractIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String COMPLETED_TOPIC = "wallet.wallet-payment-completed.v1";
    private static final String FAILED_TOPIC = "wallet.wallet-payment-failed.v1";

    // 이 클래스 전용 번호대. 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(9_100_000L);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private WalletTestCharger charger;

    @BeforeEach
    void setUp() {
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
        charger = new WalletTestCharger(jdbcTemplate, buyerWalletService);
        jdbcTemplate.update("DELETE FROM wallet_message_failure");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM wallet_message_failure");
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 주문_생성을_Kafka로_받으면_차감하고_주문_요약을_남긴_뒤_완료_메시지를_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);

        // when
        sendOrderCreated(orderId, orderCreated(orderId, buyerId, 3_000L));

        // then
        assertThat(resultsFor(COMPLETED_TOPIC, orderId, 1)).singleElement().asString()
                .startsWith("{\"orderId\":" + orderId + ",\"buyerId\":" + buyerId + ",\"amount\":3000,\"paidAt\":");
        assertThat(balanceOf(buyerId)).isEqualTo(7_000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_summary FROM wallet_order_payment WHERE order_id = ?", String.class, orderId))
                .isEqualTo("사과 외 2건");
    }

    @Test
    void 잔액이_모자라면_차감하지_않고_실패_메시지를_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();

        // when
        sendOrderCreated(orderId, orderCreated(orderId, buyerId, 3_000L));

        // then
        assertThat(resultsFor(FAILED_TOPIC, orderId, 1)).containsExactly(
                "{\"orderId\":" + orderId + ",\"buyerId\":" + buyerId + ",\"reason\":\"INSUFFICIENT_BALANCE\"}");
    }

    @Test
    void 같은_주문_생성을_두_번_받으면_한_번만_차감하고_완료_메시지는_올_때마다_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        Map<String, Object> message = orderCreated(orderId, buyerId, 3_000L);

        // when
        sendOrderCreated(orderId, message);
        sendOrderCreated(orderId, message);

        // then: 결과를 받지 못한 주문이 다시 요청해도 결과를 받도록 두 번 모두 보낸다
        List<String> results = resultsFor(COMPLETED_TOPIC, orderId, 2);
        assertThat(results).hasSize(2);
        assertThat(results.get(1)).isEqualTo(results.get(0));
        assertThat(balanceOf(buyerId)).isEqualTo(7_000);
    }

    @Test
    void 구매자_ID가_없는_주문은_원문과_함께_실패_기록에_남긴_뒤_다음_주문을_처리한다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        Map<String, Object> noBuyer = new HashMap<>(orderCreated(orderId, buyerId, 3_000L));
        noBuyer.remove("buyerId");

        // when: 같은 키(같은 파티션)로 잘못된 주문 뒤에 정상 주문을 보냄
        sendOrderCreated(orderId, noBuyer);
        sendOrderCreated(orderId, orderCreated(orderId, buyerId, 3_000L));

        // then: 뒤 주문의 결과가 나왔으면 잘못된 주문이 받기를 막지 않은 것이다
        assertThat(resultsFor(COMPLETED_TOPIC, orderId, 1)).hasSize(1);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countMessageFailures()).isOne());
        Map<String, Object> sut = jdbcTemplate.queryForMap(
                "SELECT topic, message_key, payload, failure_reason FROM wallet_message_failure");
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.get("topic")).isEqualTo(WalletKafkaTopics.ORDER_CREATED);
            softly.assertThat(sut.get("message_key")).isEqualTo(String.valueOf(orderId));
            // 실패 기록은 지갑 DTO로 읽은 값을 다시 JSON으로 바꿔 남겨, 빠진 필드는 null로 보인다
            softly.assertThat((String) sut.get("payload"))
                    .contains("\"orderId\":" + orderId, "\"buyerId\":null", "\"orderSummary\":\"사과 외 2건\"");
            softly.assertThat((String) sut.get("failure_reason")).contains("IllegalArgumentException", "구매자 ID");
        });
    }

    // 주문이 보낼 JSON (#602 약속)
    private static Map<String, Object> orderCreated(long orderId, long buyerId, long totalAmount) {
        return Map.of(
                "orderId", orderId,
                "buyerId", buyerId,
                "totalAmount", totalAmount,
                "orderSummary", "사과 외 2건");
    }

    // 주문처럼 키를 orderId로 보낸다
    private void sendOrderCreated(long orderId, Map<String, Object> message) {
        kafkaTemplate.send(WalletKafkaTopics.ORDER_CREATED, String.valueOf(orderId), message);
    }

    private List<String> resultsFor(String topic, long orderId, int expectedCount) {
        return new KafkaTopicReader(consumerFactory).valuesWithKey(topic, String.valueOf(orderId), expectedCount);
    }

    private long balanceOf(long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
    }

    private long countMessageFailures() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_message_failure", Long.class);
    }

    private static long nextId() {
        return ID_SEQ.incrementAndGet();
    }
}
