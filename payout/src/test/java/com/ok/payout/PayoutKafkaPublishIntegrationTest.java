package com.ok.payout;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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

import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.out.persistence.PayoutJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutOutboxJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutSnapshotJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutOutbox;
import com.ok.payout.domain.PayoutOutboxStatus;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.testsupport.AbstractIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트: 정산 배치가 확정한 정산을 실제 Kafka(테스트 컨테이너)의 payout.seller-payout-created 토픽으로 보내는지 확인 (#339)
// - 토픽 메시지는 테스트마다 지워지지 않으므로(다른 정산 통합 테스트도 같은 토픽에 보냄) 이번 정산의 payoutId(키)로 걸러서 본다
// - 값은 문자열로 읽어 JSON 그대로 확인한다. 지갑은 정산 클래스가 아니라 이 JSON 모양으로 읽기 때문이다
// - 정산·스냅샷·draft는 조회 하한이 정산이 있는 스냅샷(정기 실행)에서 구해지므로, 정산·스냅샷을 비워 하한 기준을 초기화하려고 테스트 전후로 전부 비운다 (PayoutSettleIntegrationTest와 같은 방식)
// - 정산후보는 created_at을 과거로 넣어야 하므로 SQL로 직접 넣는다 (저장 시 Auditing이 현재 시각으로 덮어쓰기 때문). 결과 확인은 JPA로 한다
class PayoutKafkaPublishIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문 번호대 (다른 정산 통합 테스트는 3_000_000, 4_000_000, 6_000_000, 8_000_000, 9_000_000번대)
    // 주문항목 ID에도 같은 번호를 쓰므로 정리는 order_id 이 범위로 함
    private static final long ORDER_ID_START = 2_000_000L;
    private static final long ORDER_ID_END = 3_000_000L;
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(ORDER_ID_START);

    // 토픽을 끝까지 읽는 데 기다리는 최대 시간 (보통 1초 안에 끝남)
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private PayoutSnapshotJpaRepository snapshotRepository;

    @Autowired
    private PayoutJpaRepository payoutRepository;

    @Autowired
    private PayoutOutboxJpaRepository outboxJpaRepository;

    @Autowired
    private PayoutWalletDeductionJpaRepository payoutWalletDeductionRepository;

    // yaml(spring.kafka)과 테스트 컨테이너 주소(KafkaConnectionDetails)로 자동 설정된 컨슈머 팩토리
    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 이전 테스트가 중간에 실패해 남긴 데이터가 있어도 영향받지 않도록 시작 전에도 정리
    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 확정된_판매자_몫_정산만_토픽에_한_건씩_보낸다() throws Exception {
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

        // then: 정산은 판매자 10, 판매자 20(0원), 시스템 몫 3건이 확정됨
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Payout> payouts = payoutRepository.findBySnapshotId(snapshotIdOf(execution));
        assertThat(payouts)
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(10L, 9_700L), tuple(20L, 0L), tuple(null, 300L));

        // then: 토픽에는 판매자 10의 정산만 1건 나감 (0원·시스템 몫은 안 나감). 키는 payoutId
        Long seller10PayoutId = payoutIdOf(payouts, 10L);
        Map<String, String> sent = readMessages(keysOf(payouts));
        assertThat(sent).containsOnlyKeys(String.valueOf(seller10PayoutId));

        // then: 값은 지갑과 약속한 JSON 모양 (같은 Long 필드 3개가 서로 다른 값이라 뒤바뀌면 실패함)
        JsonNode message = jsonMapper.readTree(sent.get(String.valueOf(seller10PayoutId)));
        Payout seller10 = payoutRepository.findById(seller10PayoutId).orElseThrow();
        assertThat(message).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(message.get("payoutId").asLong()).isEqualTo(seller10PayoutId);
            softly.assertThat(message.get("payeeId").asLong()).isEqualTo(10L);
            softly.assertThat(message.get("amount").asLong()).isEqualTo(9_700L);
            // 숫자 배열([2026,10,30,...])이 아니라 문자열로 나가야 지갑이 약속대로 읽음
            softly.assertThat(message.get("createdAt").isString()).isTrue();
            softly.assertThat(LocalDateTime.parse(message.get("createdAt").asString())).isEqualTo(seller10.getCreatedAt());
        });

        // then: 0원 초과 판매자 정산(10번)만 outbox 줄이 1건이고 SENT, 발송 시각이 기록됨
        PayoutOutbox outbox = singleOutboxOf(seller10PayoutId);
        assertThat(outbox.getStatus()).isEqualTo(PayoutOutboxStatus.SENT);
        assertThat(outbox.getSentAt()).isNotNull();
        assertThat(countOutboxes(payouts)).isEqualTo(1);
    }

    @Test
    void 이전_정산의_미발송_줄도_다음_실행에서_함께_보낸다() throws Exception {
        // given: 첫 실행으로 정산 1건을 만들고, 그 outbox 줄을 "아직 못 보낸" PENDING 상태로 되돌림
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        long orderA = nextOrderId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderA, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderA, 10L, 300L, createdAt);
        JobExecution first = payoutJobRunner.runScheduled();
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long oldPayoutId = payoutIdOf(payoutRepository.findBySnapshotId(snapshotIdOf(first)), 10L);
        // 리포지토리에 삭제·되돌리기가 없어, 줄을 지우고 PENDING으로 다시 저장함
        jdbcTemplate.update("DELETE FROM payout_outbox WHERE payout_id = ?", oldPayoutId);
        outboxJpaRepository.save(PayoutOutbox.pending(oldPayoutId));

        // when: 새 후보로 다시 실행 (조회 하한이 첫 실행 기준 오늘-18일로 올라가, 하한 안쪽인 16일 전으로 만듦)
        long orderB = nextOrderId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderB, 20L, 5_000L, LocalDateTime.now().minusDays(16));
        JobExecution second = payoutJobRunner.runScheduled();

        // then: 새 정산과 함께 이전 정산도 토픽에 나가고 두 줄 모두 SENT
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long newPayoutId = payoutIdOf(payoutRepository.findBySnapshotId(snapshotIdOf(second)), 20L);
        Set<String> keys = Set.of(String.valueOf(oldPayoutId), String.valueOf(newPayoutId));
        assertThat(readMessages(keys)).containsOnlyKeys(keys);
        assertThat(singleOutboxOf(oldPayoutId).getStatus()).isEqualTo(PayoutOutboxStatus.SENT);
        assertThat(singleOutboxOf(newPayoutId).getStatus()).isEqualTo(PayoutOutboxStatus.SENT);
    }

    // ---------- 도우미: outbox 확인 ----------

    private PayoutOutbox singleOutboxOf(Long payoutId) {
        List<PayoutOutbox> found = new ArrayList<>();
        for (PayoutOutbox outbox : outboxJpaRepository.findAll()) {
            if (payoutId.equals(outbox.getPayoutId())) {
                found.add(outbox);
            }
        }
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private long countOutboxes(List<Payout> payouts) {
        Set<Long> ids = new HashSet<>();
        for (Payout payout : payouts) {
            ids.add(payout.getId());
        }
        long count = 0;
        for (PayoutOutbox outbox : outboxJpaRepository.findAll()) {
            if (ids.contains(outbox.getPayoutId())) {
                count++;
            }
        }
        return count;
    }

    // ---------- 도우미: 토픽 읽기 ----------

    // 토픽의 처음부터 지금 끝 위치까지 읽어, 이번 정산의 키(payoutId)를 가진 메시지만 키 → JSON 문자열로 모은다
    // 발행은 브로커 저장 확인을 받은 뒤 끝나므로, Job이 끝난 뒤의 끝 위치까지 읽으면 이번 메시지는 모두 들어 있다
    private Map<String, String> readMessages(Set<String> keys) {
        Properties overrides = new Properties();
        // JSON을 정산 클래스로 바꾸지 않고 글자 그대로 읽는다
        overrides.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 다른 컨슈머 그룹의 읽은 위치와 섞이지 않게 매번 새 그룹으로 읽는다
        String groupId = "payout-kafka-publish-test-" + UUID.randomUUID();
        try (Consumer<?, ?> consumer = consumerFactory.createConsumer(groupId, null, null, overrides)) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (PartitionInfo info : consumer.partitionsFor(PayoutTopics.SELLER_PAYOUT_CREATED)) {
                partitions.add(new TopicPartition(info.topic(), info.partition()));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            Map<String, String> found = new HashMap<>();
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            while (!reachedEnd(consumer, endOffsets) && System.nanoTime() < deadline) {
                for (ConsumerRecord<?, ?> record : consumer.poll(Duration.ofMillis(500))) {
                    String key = (String) record.key();
                    if (keys.contains(key)) {
                        found.put(key, (String) record.value());
                    }
                }
            }
            return found;
        }
    }

    private boolean reachedEnd(Consumer<?, ?> consumer, Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (consumer.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    // 이번 스냅샷의 정산 전부(판매자·0원·시스템 몫)의 id를 키 문자열로 (보내지 않아야 할 정산도 걸러서 확인하려고 모두 넣음)
    private Set<String> keysOf(List<Payout> payouts) {
        Set<String> keys = new HashSet<>();
        for (Payout payout : payouts) {
            keys.add(String.valueOf(payout.getId()));
        }
        return keys;
    }

    private Long payoutIdOf(List<Payout> payouts, Long payeeId) {
        for (Payout payout : payouts) {
            if (payeeId.equals(payout.getPayeeId())) {
                return payout.getId();
            }
        }
        throw new IllegalStateException("정산이 없습니다. payeeId=" + payeeId);
    }

    // Job 실행 1회 = 스냅샷 1건이라, 실행 ID로 그 실행이 만든 스냅샷을 찾음 (JPA 조회)
    private Long snapshotIdOf(JobExecution execution) {
        for (PayoutSnapshot snapshot : snapshotRepository.findAll()) {
            if (snapshot.getJobExecutionId().equals(execution.getId())) {
                return snapshot.getId();
            }
        }
        throw new IllegalStateException("실행에 해당하는 스냅샷이 없습니다. executionId=" + execution.getId());
    }

    // ---------- 도우미: 데이터 준비 (JDBC, PayoutSettleIntegrationTest와 같은 SQL) ----------

    // 정산 후보를 원하는 created_at으로 직접 저장 (+ 그 주문의 지갑 차감 기록)
    // 상품 하나 = 주문 하나로 단순화해서 주문항목 ID에 주문 ID와 같은 값을 씀
    private void insertCandidate(PayoutEventType eventType, long orderId, Long sellerId, Long amount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderId, orderId, sellerId, amount, createdAt, createdAt);
        givenDeduction(orderId);
    }

    // 관리자 수동 조정 후보 (판매 대금을 깎아 0원 정산을 만들 때 씀, 관리자 ID·사유는 테스트용 고정값)
    private void insertAdjustment(long orderId, Long sellerId, Long amount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items " +
                        "(event_type, order_id, order_item_id, seller_id, amount, adjustment_id, admin_id, reason, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                PayoutEventType.SALE_AMOUNT.name(), orderId, orderId, sellerId, amount, 1L, 99L, "테스트 조정",
                createdAt, createdAt);
        givenDeduction(orderId);
    }

    // 후보의 주문에 지갑 차감 기록을 넣음 (없으면 validateStep이 정산에서 뺌, #194). 주문당 1건 (UK order_id)
    // 금액은 넉넉한 고정값: 초과 청구 검사(#195)는 "후보 합계 ≤ 차감액"이라 실제 합계로 맞추지 않아도 통과함
    private void givenDeduction(long orderId) {
        if (payoutWalletDeductionRepository.existsByOrderId(orderId)) {
            return;
        }
        payoutWalletDeductionRepository.save(
                PayoutWalletDeduction.create(orderId, 1L, 1_000_000L, LocalDateTime.now()));
    }

    private long nextOrderId() {
        return ORDER_ID_SEQ.incrementAndGet();
    }

    // 정산·draft·스냅샷은 전부, 정산 후보·차감 기록은 이 클래스 번호대만 지움 (FK 때문에 자식 테이블 먼저)
    // (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM payout_outbox");
        jdbcTemplate.update("DELETE FROM payout_item");
        jdbcTemplate.update("DELETE FROM payout");
        jdbcTemplate.update("DELETE FROM payout_seller_draft_item");
        jdbcTemplate.update("DELETE FROM payout_seller_draft");
        jdbcTemplate.update("DELETE FROM payout_system_draft_item");
        jdbcTemplate.update("DELETE FROM payout_failure");
        jdbcTemplate.update("DELETE FROM payout_snapshot_item");
        jdbcTemplate.update("DELETE FROM payout_snapshot");
        jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id > ? AND order_id <= ?",
                ORDER_ID_START, ORDER_ID_END);
        jdbcTemplate.update("DELETE FROM payout_wallet_deduction WHERE order_id > ? AND order_id <= ?",
                ORDER_ID_START, ORDER_ID_END);
    }
}
