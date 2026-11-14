package com.ok.wallet.adapter.in.event;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.service.BuyerWalletService;
import com.ok.wallet.support.KafkaTopicReader;
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

// 통합 테스트: 환불이 Kafka로 보낸 반환 요청을 지갑 리스너가 받아 돌려주고, 결과를 환불이 받을 토픽에 보내는지 확인한다 (#34)
// - 환불 코드는 쓰지 않고, 환불이 보낼 JSON(#413 약속)을 테스트가 토픽에 직접 넣는다
// - 리스너는 별도 스레드에서 받으므로 결과는 await()로 기다린 뒤 DB와 결과 토픽으로 확인한다
// - 테스트 브로커는 파티션이 하나라 같은 키로 보낸 메시지는 보낸 순서대로 처리된다. 뒤 메시지가 처리됐으면 앞 메시지도 처리가 끝난 것이다
class RefundKafkaListenerIntegrationTest extends AbstractIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String COMPLETED_TOPIC = "wallet.wallet-refund-completed";
    private static final String FAILED_TOPIC = "wallet.wallet-refund-failed";

    // 이 클래스 전용 번호대. 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(9_000_000L);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
        jdbcTemplate.update("DELETE FROM wallet_message_failure");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM wallet_message_failure");
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 환불_승인을_Kafka로_받으면_구매자_지갑에_돌려주고_완료_메시지를_보낸다() {
        // given: 50,000원 충전 뒤 35,000원 결제 → 잔액 15,000원
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();
        paidOrder(buyerId, orderId, 35_000);

        // when
        sendRefundApproved(orderId, refundApproved(refundId, orderId, buyerId, 7_000L));

        // then
        assertThat(resultsFor(COMPLETED_TOPIC, orderId, 1)).containsExactly(
                completed(refundId, orderId, buyerId, 7_000));
        assertThat(balanceOf(buyerId)).isEqualTo(22_000);
    }

    @Test
    void 같은_환불_승인을_두_번_받으면_한_번만_돌려주고_완료_메시지는_올_때마다_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();
        paidOrder(buyerId, orderId, 35_000);
        Map<String, Object> message = refundApproved(refundId, orderId, buyerId, 7_000L);

        // when
        sendRefundApproved(orderId, message);
        sendRefundApproved(orderId, message);

        // then: 환불이 다시 요청해도 결과를 받도록 두 번 모두 보낸다
        String first = completed(refundId, orderId, buyerId, 7_000);
        assertThat(resultsFor(COMPLETED_TOPIC, orderId, 2)).containsExactly(first, first);
        assertThat(balanceOf(buyerId)).isEqualTo(22_000);
    }

    @Test
    void 결제가_없는_주문의_환불_승인은_돌려주지_않고_실패_메시지를_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();

        // when
        sendRefundApproved(orderId, refundApproved(refundId, orderId, buyerId, 7_000L));

        // then
        assertThat(resultsFor(FAILED_TOPIC, orderId, 1)).containsExactly(
                "{\"refundId\":" + refundId + ",\"orderId\":" + orderId + ",\"buyerId\":" + buyerId
                        + ",\"amount\":7000,\"reason\":\"PAYMENT_NOT_FOUND\"}");
    }

    @Test
    void 금액이_0인_요청은_돌려주지_않고_원문과_함께_실패_기록에_남긴_뒤_다음_요청을_처리한다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        long badRefundId = nextId();
        long goodRefundId = nextId();
        paidOrder(buyerId, orderId, 35_000);

        // when: 같은 키(같은 파티션)로 잘못된 요청 뒤에 정상 요청을 보냄
        sendRefundApproved(orderId, refundApproved(badRefundId, orderId, buyerId, 0L));
        sendRefundApproved(orderId, refundApproved(goodRefundId, orderId, buyerId, 7_000L));

        // then: 뒤 요청의 결과가 나왔으면 잘못된 요청이 받기를 막지 않은 것이다
        assertThat(resultsFor(COMPLETED_TOPIC, orderId, 1)).containsExactly(
                completed(goodRefundId, orderId, buyerId, 7_000));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countMessageFailures()).isOne());
        Map<String, Object> sut = jdbcTemplate.queryForMap(
                "SELECT topic, message_key, payload, failure_reason FROM wallet_message_failure");
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.get("topic")).isEqualTo(WalletKafkaTopics.REFUND_APPROVED);
            softly.assertThat(sut.get("message_key")).isEqualTo(String.valueOf(orderId));
            softly.assertThat((String) sut.get("payload")).contains("\"refundId\":" + badRefundId, "\"amount\":0");
            softly.assertThat((String) sut.get("failure_reason")).contains("IllegalArgumentException", "amount가 0 이하");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_order_refund WHERE refund_id = ?", Long.class, badRefundId)).isZero();
    }

    // 환불이 보낼 JSON (#413 약속). 환불은 sellerId도 보내지만 지갑은 쓰지 않는다
    private static Map<String, Object> refundApproved(long refundId, long orderId, long buyerId, long amount) {
        return Map.of(
                "refundId", refundId,
                "orderId", orderId,
                "buyerId", buyerId,
                "sellerId", 1L,
                "amount", amount);
    }

    private static String completed(long refundId, long orderId, long buyerId, long amount) {
        return "{\"refundId\":" + refundId + ",\"orderId\":" + orderId + ",\"buyerId\":" + buyerId
                + ",\"amount\":" + amount + "}";
    }

    // 환불처럼 키를 orderId로 보낸다
    private void sendRefundApproved(long orderId, Map<String, Object> message) {
        kafkaTemplate.send(WalletKafkaTopics.REFUND_APPROVED, String.valueOf(orderId), message);
    }

    // 50,000원을 충전하고 주문을 결제한다
    private void paidOrder(long buyerId, long orderId, long amount) {
        buyerWalletService.chargeByPayment(buyerId, "pay-" + orderId, 50_000);
        buyerWalletService.payForOrder(buyerId, orderId, amount);
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
