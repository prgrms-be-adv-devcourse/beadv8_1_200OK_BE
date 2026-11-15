package com.ok.payout;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.out.persistence.PayoutDepositCheckJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.application.port.in.PayoutDepositCheckRunResult;
import com.ok.payout.application.port.in.PayoutDepositCheckUseCase;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutDepositCheck;
import com.ok.payout.domain.PayoutDepositCheckStatus;
import com.ok.payout.domain.PayoutDepositMismatchReason;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.testsupport.AbstractIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 정산·지갑 대사 (#619)
// - 정산 Job으로 정산을 실제로 확정해 대사 줄을 만들고, 대사를 돌려 질문이 실제 Kafka 토픽으로 나가는지 본다
// - 지갑 코드는 쓰지 않고, 지갑이 보낼 답 JSON(PR #548 문서 4-3절, 지갑 #613)을 테스트가 답 토픽에 직접 넣는다
// - 리스너는 별도 스레드에서 받으므로 결과는 await()로 기다린 뒤 리포지토리로 확인한다
// - 정산·스냅샷은 조회 하한 기준이 되므로 다른 정산 통합 테스트처럼 테스트 전후로 전부 비운다 (PayoutKafkaPublishIntegrationTest와 같은 방식)
class PayoutDepositCheckIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문 번호대 (다른 정산 통합 테스트가 쓰지 않는 범위). 주문항목 ID에도 같은 번호를 씀
    private static final long ORDER_ID_START = 20_000_000L;
    private static final long ORDER_ID_END = 21_000_000L;
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(ORDER_ID_START);

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private PayoutDepositCheckUseCase payoutDepositCheckUseCase;

    @Autowired
    private PayoutDepositCheckJpaRepository depositCheckRepository;

    @Autowired
    private PayoutJpaRepository payoutRepository;

    @Autowired
    private PayoutWalletDeductionJpaRepository payoutWalletDeductionRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 정산을_확정하면_지갑에_입금_메시지를_보내는_정산만_대사_줄이_생긴다() throws Exception {
        // given: 판매자 10은 9,700원(수수료 300원은 시스템 몫), 판매자 20은 5,000원에 -5,000원 조정이 붙어 0원
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        long orderA = nextOrderId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderA, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderA, 10L, 300L, createdAt);
        long orderB = nextOrderId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderB, 20L, 5_000L, createdAt);
        insertAdjustment(orderB, 20L, -5_000L, createdAt);

        // when
        JobExecution execution = payoutJobRunner.runScheduled();

        // then: 판매자 10의 정산만 확인 대기 줄 1건 (0원·시스템 몫은 지갑에 안 보내 대사도 안 함)
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<PayoutDepositCheck> checks = depositCheckRepository.findAll();
        assertThat(checks).hasSize(1);
        PayoutDepositCheck sut = checks.get(0);
        Payout seller10 = payoutRepository.findById(sut.getPayoutId()).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(seller10.getPayeeId()).isEqualTo(10L);
            softly.assertThat(sut.getPayeeId()).isEqualTo(10L);
            softly.assertThat(sut.getAmount()).isEqualTo(9_700L);
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);
            softly.assertThat(sut.getAttemptCount()).isZero();
        });
    }

    @Test
    void 대사를_돌리면_대기_줄을_묶어_약속한_모양으로_지갑에_묻는다() throws Exception {
        // given: 확인 대기 정산 1건
        Long payoutId = settleOnePayout(10L, 9_700L);

        // when
        PayoutDepositCheckRunResult result = payoutDepositCheckUseCase.runCheck();

        // then: 묶음 1개에 1건을 물었고, 줄에 물은 횟수와 묶음 번호가 남음
        assertThat(result.askedCount()).isEqualTo(1);
        PayoutDepositCheck check = checkOf(payoutId);
        assertThat(check.getAttemptCount()).isEqualTo(1);
        Long checkId = check.getLastCheckId();
        assertThat(checkId).isNotNull();

        // then: 질문 토픽에 묶음 번호(키)로 지갑과 약속한 JSON이 나감
        String sent = readMessage(PayoutTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED, String.valueOf(checkId));
        assertThat(sent).isNotNull();
        JsonNode message = jsonMapper.readTree(sent);
        JsonNode item = message.get("items").get(0);
        assertSoftly(softly -> {
            softly.assertThat(message.get("checkId").asLong()).isEqualTo(checkId);
            softly.assertThat(message.get("items").size()).isEqualTo(1);
            softly.assertThat(item.get("payoutId").asLong()).isEqualTo(payoutId);
            softly.assertThat(item.get("payeeId").asLong()).isEqualTo(10L);
            softly.assertThat(item.get("amount").asLong()).isEqualTo(9_700L);
        });
    }

    @Test
    void 지갑이_같은_판매자_금액으로_있다고_답하면_일치로_끝난다() throws Exception {
        // given: 정산 1건을 물음
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutId).getLastCheckId();

        // when: 지갑 답 (depositedAt은 오프셋 없는 ISO-8601)
        sendAnswer(checkId, payoutId, true, 10L, 9_700L, "2026-11-15T02:05:00");

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED));
        PayoutDepositCheck sut = checkOf(payoutId);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getWalletPayeeId()).isEqualTo(10L);
            softly.assertThat(sut.getWalletAmount()).isEqualTo(9_700L);
            softly.assertThat(sut.getWalletDepositedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 2, 5));
        });
    }

    @Test
    void 지갑이_금액이_다르다고_답하면_바로_관리자_확인이다() throws Exception {
        // given
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutId).getLastCheckId();

        // when
        sendAnswer(checkId, payoutId, true, 10L, 9_000L, "2026-11-15T02:05:00");

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.NEEDS_REVIEW));
        assertThat(checkOf(payoutId).getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.AMOUNT_MISMATCH);
    }

    @Test
    void 지갑이_없다고_답하면_입금_메시지를_다시_보내고_같은_답이_두_번_와도_한_번만_센다() throws Exception {
        // given
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutId).getLastCheckId();
        Map<TopicPartition, Long> before = endOffsets(PayoutTopics.SELLER_PAYOUT_CREATED);

        // when: 같은 "없음" 답이 두 번 옴
        sendAnswer(checkId, payoutId, false, null, null, null);
        sendAnswer(checkId, payoutId, false, null, null, null);

        // then: 없음은 한 번만 세고 대기로 남음
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getNotFoundCount()).isEqualTo(1));
        assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);

        // then: 입금 메시지(같은 payoutId)가 다시 나감
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(readMessage(PayoutTopics.SELLER_PAYOUT_CREATED, String.valueOf(payoutId), before)).isNotNull());
    }

    // ---------- 도우미 ----------

    // 정산 1건을 확정하고 그 정산 ID를 돌려줌 (지갑 입금 메시지도 이때 나감)
    private Long settleOnePayout(Long sellerId, Long amount) throws Exception {
        long orderId = nextOrderId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderId, sellerId, amount, LocalDateTime.now().minusDays(20));
        JobExecution execution = payoutJobRunner.runScheduled();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<PayoutDepositCheck> checks = depositCheckRepository.findAll();
        assertThat(checks).hasSize(1);
        return checks.get(0).getPayoutId();
    }

    private PayoutDepositCheck checkOf(Long payoutId) {
        List<PayoutDepositCheck> found = depositCheckRepository.findAllByPayoutIdIn(List.of(payoutId));
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private void sendAnswer(Long checkId, Long payoutId, boolean found, Long payeeId, Long amount, String depositedAt) {
        Map<String, Object> item = new HashMap<>();
        item.put("payoutId", payoutId);
        item.put("found", found);
        item.put("payeeId", payeeId);
        item.put("amount", amount);
        item.put("depositedAt", depositedAt);
        Map<String, Object> message = Map.of("checkId", checkId, "items", List.of(item));
        kafkaTemplate.send(PayoutTopics.WALLET_PAYOUT_DEPOSIT_CHECKED, String.valueOf(checkId), message);
    }

    private String readMessage(String topic, String key) {
        return readMessage(topic, key, Map.of());
    }

    // 토픽을 처음(또는 from 위치)부터 지금 끝까지 읽어 그 키의 마지막 메시지를 JSON 문자열로 돌려줌. 없으면 null
    private String readMessage(String topic, String key, Map<TopicPartition, Long> from) {
        try (Consumer<?, ?> consumer = createConsumer()) {
            List<TopicPartition> partitions = assignTopic(consumer, topic);
            consumer.seekToBeginning(partitions);
            for (Map.Entry<TopicPartition, Long> entry : from.entrySet()) {
                consumer.seek(entry.getKey(), entry.getValue());
            }
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            String found = null;
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!reachedEnd(consumer, endOffsets) && System.nanoTime() < deadline) {
                for (ConsumerRecord<?, ?> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        found = (String) record.value();
                    }
                }
            }
            return found;
        }
    }

    private Map<TopicPartition, Long> endOffsets(String topic) {
        try (Consumer<?, ?> consumer = createConsumer()) {
            return consumer.endOffsets(assignTopic(consumer, topic));
        }
    }

    private Consumer<?, ?> createConsumer() {
        Properties overrides = new Properties();
        // JSON을 정산 클래스로 바꾸지 않고 글자 그대로 읽는다
        overrides.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 다른 컨슈머 그룹의 읽은 위치와 섞이지 않게 매번 새 그룹으로 읽는다
        return consumerFactory.createConsumer("payout-deposit-check-test-" + UUID.randomUUID(), null, null, overrides);
    }

    private List<TopicPartition> assignTopic(Consumer<?, ?> consumer, String topic) {
        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : consumer.partitionsFor(topic)) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        consumer.assign(partitions);
        return partitions;
    }

    private boolean reachedEnd(Consumer<?, ?> consumer, Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (consumer.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    // 정산 후보를 원하는 created_at으로 직접 저장 (+ 그 주문의 지갑 차감 기록). PayoutKafkaPublishIntegrationTest와 같은 SQL
    private void insertCandidate(PayoutEventType eventType, long orderId, Long sellerId, Long amount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderId, orderId, sellerId, amount, createdAt, createdAt);
        givenDeduction(orderId);
    }

    private void insertAdjustment(long orderId, Long sellerId, Long amount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items " +
                        "(event_type, order_id, order_item_id, seller_id, amount, adjustment_id, admin_id, reason, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                PayoutEventType.SALE_AMOUNT.name(), orderId, orderId, sellerId, amount, 1L, 99L, "테스트 조정",
                createdAt, createdAt);
        givenDeduction(orderId);
    }

    // 후보의 주문에 지갑 차감 기록을 넣음 (없으면 validateStep이 정산에서 뺌, #194). 금액은 넉넉한 고정값
    private void givenDeduction(long orderId) {
        if (payoutWalletDeductionRepository.existsByOrderId(orderId)) {
            return;
        }
        payoutWalletDeductionRepository.save(PayoutWalletDeduction.create(orderId, 1L, 1_000_000L, LocalDateTime.now()));
    }

    private long nextOrderId() {
        return ORDER_ID_SEQ.incrementAndGet();
    }

    // 정산·draft·스냅샷·대사는 전부, 정산 후보·차감 기록은 이 클래스 번호대만 지움 (FK 때문에 자식 테이블 먼저)
    // 질문 묶음 표는 대사 줄이 가리키므로 대사 줄 뒤에 지움 (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM payout_deposit_check");
        jdbcTemplate.update("DELETE FROM payout_deposit_check_batch");
        jdbcTemplate.update("DELETE FROM payout_outbox");
        jdbcTemplate.update("DELETE FROM payout_item");
        jdbcTemplate.update("DELETE FROM payout");
        jdbcTemplate.update("DELETE FROM payout_seller_draft_item");
        jdbcTemplate.update("DELETE FROM payout_seller_draft");
        jdbcTemplate.update("DELETE FROM payout_system_draft_item");
        jdbcTemplate.update("DELETE FROM payout_failure_resolution");
        jdbcTemplate.update("DELETE FROM payout_failure");
        jdbcTemplate.update("DELETE FROM payout_snapshot_item");
        jdbcTemplate.update("DELETE FROM payout_snapshot");
        jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id > ? AND order_id <= ?",
                ORDER_ID_START, ORDER_ID_END);
        jdbcTemplate.update("DELETE FROM payout_wallet_deduction WHERE order_id > ? AND order_id <= ?",
                ORDER_ID_START, ORDER_ID_END);
    }
}
