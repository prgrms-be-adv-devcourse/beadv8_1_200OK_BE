package com.ok.wallet.adapter.in.event;

import com.ok.testsupport.AbstractIntegrationTest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 주문이 Kafka로 보낸 결제 만료 요청을 지갑 리스너가 받아 결과를 주문이 받을 토픽에 보내는지 확인한다 (#693)
// - 주문 코드는 쓰지 않고, 주문이 보낼 JSON(#656 약속)을 테스트가 토픽에 직접 넣는다
// - 처리 기록 상태별 결과는 OrderPaymentExpireServiceIntegrationTest가 본다. 여기서는 받기부터 결과까지 이어지는지 본다
// - 테스트 브로커는 파티션이 하나라 같은 키로 보낸 메시지는 보낸 순서대로 처리된다
class OrderPaymentExpireKafkaListenerIntegrationTest extends AbstractIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String FAILED_TOPIC = "wallet.wallet-payment-failed.v1";

    // 이 클래스 전용 번호대. 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(9_300_000L);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

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
    void 처음_보는_주문의_만료_요청을_Kafka로_받으면_만료로_기록하고_결제_만료_실패를_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();

        // when
        sendExpireRequested(orderId, expireRequested(orderId, buyerId, 3_000L));

        // then
        assertThat(resultsFor(FAILED_TOPIC, orderId, 1)).containsExactly(
                "{\"orderId\":" + orderId + ",\"buyerId\":" + buyerId + ",\"reason\":\"PAYMENT_EXPIRED\"}");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(orderId)).containsExactly("EXPIRED"));
    }

    @Test
    void 금액이_없는_만료_요청은_원문과_함께_실패_기록에_남긴_뒤_다음_요청을_처리한다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        Map<String, Object> noAmount = new HashMap<>(expireRequested(orderId, buyerId, 3_000L));
        noAmount.remove("totalAmount");

        // when: 같은 키(같은 파티션)로 잘못된 요청 뒤에 정상 요청을 보냄
        sendExpireRequested(orderId, noAmount);
        sendExpireRequested(orderId, expireRequested(orderId, buyerId, 3_000L));

        // then: 뒤 요청의 결과가 나왔으면 잘못된 요청이 받기를 막지 않은 것이다
        assertThat(resultsFor(FAILED_TOPIC, orderId, 1)).hasSize(1);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countMessageFailures()).isOne());
        Map<String, Object> sut = jdbcTemplate.queryForMap(
                "SELECT topic, message_key, payload, failure_reason FROM wallet_message_failure");
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.get("topic")).isEqualTo(WalletKafkaTopics.ORDER_PAYMENT_EXPIRE_REQUESTED);
            softly.assertThat(sut.get("message_key")).isEqualTo(String.valueOf(orderId));
            softly.assertThat((String) sut.get("payload")).contains("\"orderId\":" + orderId, "\"totalAmount\":null");
            softly.assertThat((String) sut.get("failure_reason")).contains("IllegalArgumentException", "0보다 큰 금액");
        });
    }

    // 주문이 보낼 JSON (#656 약속)
    private static Map<String, Object> expireRequested(long orderId, long buyerId, long totalAmount) {
        return Map.of(
                "orderId", orderId,
                "buyerId", buyerId,
                "totalAmount", totalAmount);
    }

    // 주문처럼 키를 orderId로 보낸다
    private void sendExpireRequested(long orderId, Map<String, Object> message) {
        kafkaTemplate.send(WalletKafkaTopics.ORDER_PAYMENT_EXPIRE_REQUESTED, String.valueOf(orderId), message);
    }

    private List<String> resultsFor(String topic, long orderId, int expectedCount) {
        return new KafkaTopicReader(consumerFactory).valuesWithKey(topic, String.valueOf(orderId), expectedCount);
    }

    private List<String> statusOf(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM wallet_order_payment WHERE order_id = ?", String.class, orderId);
    }

    private long countMessageFailures() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_message_failure", Long.class);
    }

    private static long nextId() {
        return ID_SEQ.incrementAndGet();
    }
}
