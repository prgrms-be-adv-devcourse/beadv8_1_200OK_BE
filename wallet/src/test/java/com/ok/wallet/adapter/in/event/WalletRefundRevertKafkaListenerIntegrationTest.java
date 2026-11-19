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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 환불이 Kafka로 보낸 되돌리기 요청을 지갑 리스너가 받아 처리하고, 결과를 환불이 받을 토픽에 약속한 모양(#611)으로 보내는지 확인한다
// - 환불 코드는 쓰지 않고, 환불이 보낼 JSON을 테스트가 토픽에 직접 넣고 나간 JSON을 문자열 그대로 읽는다
// - 테스트 브로커는 파티션이 하나라 같은 키로 보낸 메시지는 보낸 순서대로 처리된다
class WalletRefundRevertKafkaListenerIntegrationTest extends AbstractIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String REVERTED_TOPIC = "wallet.wallet-refund-reverted.v1";
    private static final String REVERT_FAILED_TOPIC = "wallet.wallet-refund-revert-failed.v1";

    // 이 클래스 전용 번호대. 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(9_500_000L);

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
        charger = new WalletTestCharger(jdbcTemplate, buyerWalletService);
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
        jdbcTemplate.update("DELETE FROM wallet_message_failure");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM wallet_message_failure");
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 되돌리기를_Kafka로_받으면_돌려준_예치금을_다시_가져오고_다시_가져옴_메시지를_보낸다() {
        // given: 50,000원 충전, 35,000원 결제, 7,000원 반환 → 잔액 22,000원
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();
        refundedOrder(buyerId, orderId, refundId, 7_000);

        // when
        sendRevertRequested(orderId, revertRequested(refundId, orderId, buyerId, 7_000L));

        // then
        assertThat(resultsFor(REVERTED_TOPIC, orderId, 1)).containsExactly(
                reverted(refundId, orderId, buyerId, 7_000));
        assertThat(balanceOf(buyerId)).isEqualTo(15_000);
    }

    @Test
    void 요청의_구매자와_금액이_달라도_결과에는_지갑이_실제로_돌려준_값을_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();
        refundedOrder(buyerId, orderId, refundId, 7_000);

        // when
        sendRevertRequested(orderId, revertRequested(refundId, orderId, buyerId + 1, 9_000L));

        // then
        assertThat(resultsFor(REVERTED_TOPIC, orderId, 1)).containsExactly(
                reverted(refundId, orderId, buyerId, 7_000));
        assertThat(balanceOf(buyerId)).isEqualTo(15_000);
    }

    @Test
    void 잔액이_모자라면_가져오지_않고_실패_메시지를_보낸다() {
        // given: 돌려받은 뒤 20,000원을 더 써서 잔액 2,000원
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();
        refundedOrder(buyerId, orderId, refundId, 7_000);
        buyerWalletService.payForOrder(buyerId, nextId(), 20_000);

        // when
        sendRevertRequested(orderId, revertRequested(refundId, orderId, buyerId, 7_000L));

        // then
        assertThat(resultsFor(REVERT_FAILED_TOPIC, orderId, 1)).containsExactly(
                "{\"refundId\":" + refundId + ",\"orderId\":" + orderId + ",\"buyerId\":" + buyerId
                        + ",\"amount\":7000,\"reason\":\"INSUFFICIENT_BALANCE\"}");
        assertThat(balanceOf(buyerId)).isEqualTo(2_000);
    }

    @Test
    void 같은_되돌리기를_두_번_받으면_한_번만_가져오고_결과는_올_때마다_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();
        refundedOrder(buyerId, orderId, refundId, 7_000);
        Map<String, Object> message = revertRequested(refundId, orderId, buyerId, 7_000L);

        // when
        sendRevertRequested(orderId, message);
        sendRevertRequested(orderId, message);

        // then
        String first = reverted(refundId, orderId, buyerId, 7_000);
        assertThat(resultsFor(REVERTED_TOPIC, orderId, 2)).containsExactly(first, first);
        assertThat(balanceOf(buyerId)).isEqualTo(15_000);
    }

    @Test
    void 금액이_0인_요청은_처리하지_않고_원문과_함께_실패_기록에_남긴_뒤_다음_요청을_처리한다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        long badRefundId = nextId();
        long goodRefundId = nextId();
        refundedOrder(buyerId, orderId, goodRefundId, 7_000);

        // when: 같은 키(같은 파티션)로 잘못된 요청 뒤에 정상 요청을 보냄
        sendRevertRequested(orderId, revertRequested(badRefundId, orderId, buyerId, 0L));
        sendRevertRequested(orderId, revertRequested(goodRefundId, orderId, buyerId, 7_000L));

        // then: 뒤 요청의 결과가 나왔으면 잘못된 요청이 받기를 막지 않은 것이다
        assertThat(resultsFor(REVERTED_TOPIC, orderId, 1)).containsExactly(
                reverted(goodRefundId, orderId, buyerId, 7_000));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countMessageFailures()).isOne());
        Map<String, Object> sut = jdbcTemplate.queryForMap(
                "SELECT topic, message_key, payload, failure_reason FROM wallet_message_failure");
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.get("topic")).isEqualTo(WalletKafkaTopics.REFUND_WALLET_REVERT_REQUESTED);
            softly.assertThat(sut.get("message_key")).isEqualTo(String.valueOf(orderId));
            softly.assertThat((String) sut.get("payload")).contains("\"refundId\":" + badRefundId, "\"amount\":0");
            softly.assertThat((String) sut.get("failure_reason")).contains("IllegalArgumentException", "amount가 0 이하");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_order_refund WHERE refund_id = ?", Long.class, badRefundId)).isZero();
    }

    // 환불이 보낼 JSON (#611 약속)
    private static Map<String, Object> revertRequested(long refundId, long orderId, long buyerId, long amount) {
        return Map.of(
                "refundId", refundId,
                "orderId", orderId,
                "buyerId", buyerId,
                "amount", amount);
    }

    private static String reverted(long refundId, long orderId, long buyerId, long amount) {
        return "{\"refundId\":" + refundId + ",\"orderId\":" + orderId + ",\"buyerId\":" + buyerId
                + ",\"amount\":" + amount + "}";
    }

    // 환불처럼 키를 orderId로 보낸다
    private void sendRevertRequested(long orderId, Map<String, Object> message) {
        kafkaTemplate.send(WalletKafkaTopics.REFUND_WALLET_REVERT_REQUESTED, String.valueOf(orderId), message);
    }

    // 50,000원을 충전하고 35,000원을 결제한 뒤 반환한다
    private void refundedOrder(long buyerId, long orderId, long refundId, long amount) {
        charger.charge(buyerId, "pay-" + orderId, 50_000);
        buyerWalletService.payForOrder(buyerId, orderId, 35_000);
        buyerWalletService.refundForOrder(refundId, orderId, buyerId, amount);
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
