package com.ok.wallet.adapter.in.event;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 정산이 Kafka로 보낸 정산금 확정을 지갑 리스너가 받아 입금하는지, 처리하지 못한 메시지를 실패 기록에 남기는지 확인 (#579)
// - 정산 코드는 쓰지 않고, 정산이 보낼 JSON(#339 약속)을 테스트가 토픽에 직접 넣는다
// - 리스너는 별도 스레드에서 받으므로 결과는 await()로 기다린 뒤 DB로 확인한다
// - 테스트 브로커는 파티션이 하나라 같은 키로 보낸 메시지는 보낸 순서대로 처리된다. 뒤 메시지가 처리됐으면 앞 메시지도 처리가 끝난 것이다
class PayoutKafkaListenerIntegrationTest extends AbstractIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String SAME_KEY = "same-key";

    // 이 클래스 전용 번호대. 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 정산 번호·판매자 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(7_000_000L);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    ProducerFactory<String, Object> producerFactory;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationContext applicationContext;

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
    void 정산금_확정을_Kafka로_받으면_판매자_지갑에_입금된다() {
        // given
        long payoutId = nextId();
        long sellerId = nextId();

        // when
        sendPayout(String.valueOf(payoutId), payoutMessage(payoutId, sellerId, 9_700L));

        // then
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sellerBalance(sellerId)).isEqualTo(9_700L));
        assertThat(sellerLedgerKeys(sellerId)).containsExactly("PAYOUT:" + payoutId);
    }

    @Test
    void 같은_정산금_확정을_두_번_받아도_한_번만_입금된다() {
        // given: 처음 받은 정산이 입금될 때까지 기다림
        long payoutId = nextId();
        long sellerId = nextId();
        sendPayout(SAME_KEY, payoutMessage(payoutId, sellerId, 9_700L));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sellerBalance(sellerId)).isEqualTo(9_700L));

        // when: 같은 메시지를 다시 보내고, 뒤이어 다른 판매자의 정산을 보냄
        sendPayout(SAME_KEY, payoutMessage(payoutId, sellerId, 9_700L));
        long laterSellerId = nextId();
        sendPayout(SAME_KEY, payoutMessage(nextId(), laterSellerId, 1_000L));

        // then: 뒤 정산이 입금됐으면 다시 보낸 메시지도 처리가 끝난 것이다
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sellerBalance(laterSellerId)).isEqualTo(1_000L));
        assertThat(sellerBalance(sellerId)).isEqualTo(9_700L);
        assertThat(sellerLedgerKeys(sellerId)).hasSize(1);
    }

    @Test
    void 금액이_0인_정산은_입금하지_않고_원문과_함께_실패_기록에_남긴_뒤_다음_메시지를_처리한다() {
        // given
        long badPayoutId = nextId();
        long badSellerId = nextId();
        long goodSellerId = nextId();

        // when: 같은 키(같은 파티션)로 잘못된 메시지 뒤에 정상 메시지를 보냄
        sendPayout(SAME_KEY, payoutMessage(badPayoutId, badSellerId, 0L));
        sendPayout(SAME_KEY, payoutMessage(nextId(), goodSellerId, 1_000L));

        // then: 뒤 메시지가 처리됐으면 잘못된 메시지가 받기를 막지 않은 것이다
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sellerBalance(goodSellerId)).isEqualTo(1_000L));
        // 잔액 조회는 지갑이 없어도 0이라, 서비스까지 가지 않아 지갑도 만들어지지 않았는지는 지갑 행으로 따로 본다
        assertThat(countSellerWallets(badSellerId)).isZero();
        Map<String, Object> sut = jdbcTemplate.queryForMap(
                "SELECT topic, message_key, payload, failure_reason, status FROM wallet_message_failure");
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.get("topic")).isEqualTo(WalletKafkaTopics.SELLER_PAYOUT_CREATED);
            softly.assertThat(sut.get("message_key")).isEqualTo(SAME_KEY);
            softly.assertThat((String) sut.get("payload")).contains("\"payoutId\":" + badPayoutId, "\"amount\":0");
            softly.assertThat((String) sut.get("failure_reason")).contains("IllegalArgumentException", "amount가 0 이하");
            softly.assertThat(sut.get("status")).isEqualTo("PENDING");
        });
    }

    @Test
    void JSON이_아닌_메시지는_받은_그대로_실패_기록에_남긴_뒤_다음_메시지를_처리한다() {
        // given
        long goodSellerId = nextId();

        // when: 같은 키(같은 파티션)로 JSON이 아닌 문자열 뒤에 정상 메시지를 보냄
        stringKafkaTemplate().send(WalletKafkaTopics.SELLER_PAYOUT_CREATED, SAME_KEY, "이건 JSON이 아니다");
        sendPayout(SAME_KEY, payoutMessage(nextId(), goodSellerId, 1_000L));

        // then
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sellerBalance(goodSellerId)).isEqualTo(1_000L));
        assertThat(jdbcTemplate.queryForList("SELECT payload FROM wallet_message_failure", String.class))
                .containsExactly("이건 JSON이 아니다");
    }

    @Test
    void 지갑_수신_설정은_다른_모듈이_쓰는_기본_수신_설정을_대신하지_않는다() {
        // given: Spring Boot는 기본 이름의 수신 설정이 없을 때만 기본 설정을 만든다.
        // 지갑 설정이 기본 이름을 쓰면 기본 설정이 만들어지지 않고, 수신 설정을 따로 고르지 않은 다른 모듈의 리스너까지 지갑 설정(지갑 실패 기록)을 쓰게 된다
        Object walletFactory = applicationContext.getBean(WalletKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY);

        // when
        Object sut = applicationContext.getBean("kafkaListenerContainerFactory");

        // then
        assertThat(sut).isNotSameAs(walletFactory);
    }

    // 정산이 보낼 JSON (#339 4-1 약속). createdAt은 초 아래 자리가 붙을 수 있다
    private static Map<String, Object> payoutMessage(long payoutId, long sellerId, long amount) {
        return Map.of(
                "payoutId", payoutId,
                "payeeId", sellerId,
                "amount", amount,
                "createdAt", "2026-11-14T14:00:00.123456");
    }

    private void sendPayout(String key, Map<String, Object> message) {
        kafkaTemplate.send(WalletKafkaTopics.SELLER_PAYOUT_CREATED, key, message);
    }

    // 앱의 발행 설정(접속 주소)은 그대로 쓰고 값만 JSON 변환 없이 문자열로 보낸다
    private KafkaTemplate<String, String> stringKafkaTemplate() {
        Map<String, Object> config = new HashMap<>(producerFactory.getConfigurationProperties());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer()));
    }

    private long sellerBalance(long sellerId) {
        List<Long> balances = jdbcTemplate.queryForList(
                "SELECT balance FROM seller_wallet WHERE member_id = ?", Long.class, sellerId);
        return balances.isEmpty() ? 0L : balances.getFirst();
    }

    private long countSellerWallets(long sellerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seller_wallet WHERE member_id = ?", Long.class, sellerId);
    }

    private List<String> sellerLedgerKeys(long sellerId) {
        return jdbcTemplate.queryForList("""
                SELECT l.reference_key FROM seller_wallet_ledger l
                JOIN seller_wallet w ON w.id = l.seller_wallet_id
                WHERE w.member_id = ?
                """, String.class, sellerId);
    }

    private static long nextId() {
        return ID_SEQ.incrementAndGet();
    }
}
