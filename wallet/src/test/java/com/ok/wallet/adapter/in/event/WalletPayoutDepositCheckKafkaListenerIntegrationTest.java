package com.ok.wallet.adapter.in.event;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.port.in.SellerWalletUseCase;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 정산이 Kafka로 보낸 입금 확인 묶음을 지갑 리스너가 받아, 원장을 읽은 답을 정산이 받을 토픽에 보내는지 확인한다
// - 정산 코드는 쓰지 않고, 정산이 보낼 JSON(정산 대사 문서 4-2)을 테스트가 토픽에 직접 넣는다
// - 입금은 실제 정산금 입금 기능으로 해서 잔액과 원장이 맞는지 끝에서 검사한다. 입금 시각의 글자 모양은 WalletPayoutDepositCheckServiceIntegrationTest가 본다
// - 테스트 브로커는 파티션이 하나라 같은 키로 보낸 메시지는 보낸 순서대로 처리된다
class WalletPayoutDepositCheckKafkaListenerIntegrationTest extends AbstractIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String CHECKED_TOPIC = "wallet.payout-deposit-checked";
    private static final Pattern PAYOUT_ID_FIELD = Pattern.compile("\"payoutId\":");
    private static final Pattern FOUND_TRUE = Pattern.compile("\"found\":true");

    // 이 클래스 전용 번호대. 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(8_700_000L);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    SellerWalletUseCase sellerWalletUseCase;

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
    void 입금된_정산은_판매자와_금액을_입금되지_않은_정산은_없음을_답한다() {
        // given
        long sellerId = nextId();
        long checkId = nextId();
        long depositedPayoutId = nextId();
        long missingPayoutId = nextId();
        sellerWalletUseCase.depositPayout(sellerId, depositedPayoutId, 9_700);

        // when
        sendCheckRequested(checkId, checkRequested(checkId,
                item(depositedPayoutId, sellerId, 9_700L), item(missingPayoutId, sellerId, 12_000L)));

        // then
        assertThat(answersFor(checkId, 1)).singleElement().satisfies(answer -> assertThat(answer)
                .startsWith("{\"checkId\":" + checkId + ",\"items\":[")
                .contains(found(depositedPayoutId, sellerId, 9_700))
                .endsWith(notFound(missingPayoutId) + "]}"));
    }

    @Test
    void 같은_묶음을_두_번_받으면_같은_답을_두_번_보내고_잔액은_그대로다() {
        // given
        long sellerId = nextId();
        long checkId = nextId();
        long payoutId = nextId();
        sellerWalletUseCase.depositPayout(sellerId, payoutId, 9_700);
        Map<String, Object> message = checkRequested(checkId, item(payoutId, sellerId, 9_700L));

        // when
        sendCheckRequested(checkId, message);
        sendCheckRequested(checkId, message);

        // then: 확인 요청은 입금이 아니므로 원장이 늘지 않는다
        List<String> answers = answersFor(checkId, 2);
        assertThat(answers.get(1)).isEqualTo(answers.get(0));
        assertThat(answers.get(0)).contains(found(payoutId, sellerId, 9_700));
        assertSoftly(softly -> {
            softly.assertThat(sellerWalletUseCase.getBalance(sellerId)).isEqualTo(9_700);
            softly.assertThat(countSellerLedgers()).isOne();
        });
    }

    @Test
    void 정산_ID가_없는_항목만_빼고_나머지는_답한다() {
        // given
        long sellerId = nextId();
        long checkId = nextId();
        long payoutId = nextId();
        sellerWalletUseCase.depositPayout(sellerId, payoutId, 9_700);

        // when
        sendCheckRequested(checkId, checkRequested(checkId, item(null, sellerId, 5_000L), item(payoutId, sellerId, 9_700L)));

        // then: 항목이 하나만 남았으면 답이 그 항목으로 시작해 바로 끝난다
        String sut = answersFor(checkId, 1).getFirst();
        assertThat(sut).startsWith("{\"checkId\":" + checkId + ",\"items\":[" + found(payoutId, sellerId, 9_700));
        assertThat(PAYOUT_ID_FIELD.matcher(sut).results().count()).isOne();
    }

    @Test
    void 묶음_번호가_없는_요청은_답하지_않고_원문과_함께_실패_기록에_남긴_뒤_다음_요청을_처리한다() {
        // given
        long sellerId = nextId();
        long goodCheckId = nextId();
        long payoutId = nextId();
        String key = String.valueOf(goodCheckId);
        Map<String, Object> broken = new HashMap<>(checkRequested(goodCheckId, item(payoutId, sellerId, 9_700L)));
        broken.remove("checkId");

        // when: 같은 키(같은 파티션)로 틀린 묶음 뒤에 정상 묶음을 보냄
        kafkaTemplate.send(WalletKafkaTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED, key, broken);
        sendCheckRequested(goodCheckId, checkRequested(goodCheckId, item(payoutId, sellerId, 9_700L)));

        // then: 뒤 묶음의 답이 나왔으면 틀린 묶음이 받기를 막지 않은 것이다
        assertThat(answersFor(goodCheckId, 1)).containsExactly(
                "{\"checkId\":" + goodCheckId + ",\"items\":[" + notFound(payoutId) + "]}");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countMessageFailures()).isOne());
        Map<String, Object> sut = jdbcTemplate.queryForMap(
                "SELECT topic, message_key, payload, failure_reason FROM wallet_message_failure");
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.get("topic")).isEqualTo(WalletKafkaTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED);
            softly.assertThat(sut.get("message_key")).isEqualTo(key);
            softly.assertThat((String) sut.get("payload")).contains("\"payoutId\":" + payoutId);
            softly.assertThat((String) sut.get("failure_reason")).contains("IllegalArgumentException", "checkId 없음");
        });
    }

    @Test
    void 정산이_보내는_가장_큰_묶음_500건에_한_번에_답한다() {
        // given: 500건 중 첫 정산만 입금됨
        long sellerId = nextId();
        long checkId = nextId();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            items.add(item(nextId(), sellerId, 1_000L));
        }
        sellerWalletUseCase.depositPayout(sellerId, (Long) items.getFirst().get("payoutId"), 1_000);

        // when
        sendCheckRequested(checkId, Map.of("checkId", checkId, "items", items));

        // then
        String answer = answersFor(checkId, 1).getFirst();
        assertSoftly(softly -> {
            softly.assertThat(PAYOUT_ID_FIELD.matcher(answer).results().count()).isEqualTo(500);
            softly.assertThat(FOUND_TRUE.matcher(answer).results().count()).isOne();
        });
    }

    // 정산이 보낼 JSON. payeeId·amount는 정산이 기대하는 값이라 지갑은 답에 쓰지 않는다
    @SafeVarargs
    private static Map<String, Object> checkRequested(long checkId, Map<String, Object>... items) {
        return Map.of("checkId", checkId, "items", List.of(items));
    }

    // payoutId가 null인 항목을 만들려고 Map.of 대신 HashMap을 쓴다
    private static Map<String, Object> item(Long payoutId, long payeeId, long amount) {
        Map<String, Object> item = new HashMap<>();
        item.put("payoutId", payoutId);
        item.put("payeeId", payeeId);
        item.put("amount", amount);
        return item;
    }

    // 입금 시각은 실제 입금 시각이라 값은 보지 않고 필드가 이어지는지만 본다
    private static String found(long payoutId, long payeeId, long amount) {
        return "{\"payoutId\":" + payoutId + ",\"found\":true,\"payeeId\":" + payeeId + ",\"amount\":" + amount
                + ",\"depositedAt\":\"";
    }

    private static String notFound(long payoutId) {
        return "{\"payoutId\":" + payoutId + ",\"found\":false,\"payeeId\":null,\"amount\":null,\"depositedAt\":null}";
    }

    // 정산처럼 키를 checkId로 보낸다
    private void sendCheckRequested(long checkId, Map<String, Object> message) {
        kafkaTemplate.send(WalletKafkaTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED, String.valueOf(checkId), message);
    }

    private List<String> answersFor(long checkId, int expectedCount) {
        return new KafkaTopicReader(consumerFactory).valuesWithKey(CHECKED_TOPIC, String.valueOf(checkId), expectedCount);
    }

    private long countSellerLedgers() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_wallet_ledger", Long.class);
    }

    private long countMessageFailures() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_message_failure", Long.class);
    }

    private static long nextId() {
        return ID_SEQ.incrementAndGet();
    }
}
