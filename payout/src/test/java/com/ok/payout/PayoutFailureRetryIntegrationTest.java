package com.ok.payout;

import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.out.persistence.PayoutCandidateItemJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutSnapshotJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutSnapshotItemJpaRepository;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.application.service.PayoutService;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutSnapshotItem;
import com.ok.payout.domain.type.PayoutRunType;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

// 통합 테스트: 주문별 "성공한 실행에서 실패한 횟수" 조회와 다시 검사할 주문의 후보 조회 (#549)
// - 실행(COMPLETED)은 실제 정산 Job(PayoutJobRunner.runScheduled)을 돌려 만든다. 배치 메타 테이블에 직접 넣지 않음
// - 실패 기록은 실행이 만든 스냅샷에 SQL로 넣는다: 여러 실행에 걸친 실패 이력을 원하는 모양으로 만들려고
//   (실패 기록의 후보는 스냅샷에 들어 있지 않아도 됨. 실제 Job은 정산 대기 중인 후보를 대상으로 잡지 않음)
// - FAILED 실행은 실제 Job을 돌린 뒤 실행 기록의 상태만 FAILED로 바꿔 만든다 (JobRepository.update).
//   draft 단계를 일부러 실패시키려면 아직 없는 스냅샷 ID에 막는 행을 미리 넣어야 해서 실제 Job만으로는 만들 수 없음
// - 결과 확인은 포트(PayoutQueryRepository)와 JPA 리포지토리로 한다. JDBC는 데이터 준비·정리에만 씀
class PayoutFailureRetryIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문 ID 번호대 (다른 통합 테스트는 2_000_000, 3_000_000, 4_000_000, 6_000_000, 8_000_000~ 사용)
    private static final long ORDER_ID_START = 5_000_000L;
    private static final long ORDER_ID_END = 5_100_000L;
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(ORDER_ID_START);

    // 주문항목 ID 번호대: 주문 ID와 겹치지 않게 따로 발급 (UK(주문항목, 타입)이라 후보마다 달라야 함)
    // 직접 만드는 스냅샷의 가짜 실행 ID (실제 배치 실행 ID와 겹치지 않게 큰 값)
    private static final AtomicLong SNAPSHOT_EXECUTION_ID_SEQ = new AtomicLong(900_000_000L);

    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(15_000_000L);

    @Autowired
    private PayoutQueryRepository payoutQueryRepository;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PayoutSnapshotJpaRepository snapshotRepository;

    @Autowired
    private PayoutJpaRepository payoutRepository;

    @Autowired
    private PayoutCandidateItemJpaRepository candidateRepository;

    @Autowired
    private PayoutWalletDeductionJpaRepository walletDeductionRepository;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private PayoutSnapshotItemJpaRepository snapshotItemRepository;

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
    void 성공한_실행의_실패만_주문별_실행_수로_센다() throws Exception {
        // given: 주문 A는 성공한 실행 2번에서 실패, 주문 B는 1번에서만 실패, 주문 C는 실패 없음
        long orderA = nextOrderId();
        long orderB = nextOrderId();
        long orderC = nextOrderId();
        long candidateA = insertRecentCandidate(orderA);
        long candidateB = insertRecentCandidate(orderB);
        insertRecentCandidate(orderC);
        Long firstSnapshotId = runCompleted();
        Long secondSnapshotId = runCompleted();
        insertFailure(firstSnapshotId, candidateA, orderA, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(secondSnapshotId, candidateA, orderA, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(secondSnapshotId, candidateB, orderB, PayoutFailureReason.DEDUCTION_MISSING);

        // when
        Map<Long, Long> sut = payoutQueryRepository.countFailedRunsByOrder();

        // then: 실패가 없는 주문 C는 결과에 없음
        assertThat(sut).containsEntry(orderA, 2L).containsEntry(orderB, 1L).doesNotContainKey(orderC);
    }

    @Test
    void 한_실행에서_같은_주문의_실패_기록이_여러_줄이어도_1번으로_센다() throws Exception {
        // given: 한 실행에서 같은 주문의 후보 두 건이 서로 다른 사유로 실패
        long order = nextOrderId();
        long candidateOne = insertRecentCandidate(order);
        long candidateTwo = insertRecentCandidate(order);
        Long snapshotId = runCompleted();
        insertFailure(snapshotId, candidateOne, order, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(snapshotId, candidateTwo, order, PayoutFailureReason.UNEXPECTED_ERROR);

        // when
        Map<Long, Long> sut = payoutQueryRepository.countFailedRunsByOrder();

        // then: 실패 기록은 2줄이지만 실행은 1번
        assertThat(sut).containsEntry(order, 1L);
    }

    @Test
    void 실패로_끝난_실행의_실패는_세지_않는다() throws Exception {
        // given: 주문 A는 성공한 실행 1번 + 실패로 끝난 실행 1번에서 실패, 주문 B는 실패로 끝난 실행에서만 실패
        long orderA = nextOrderId();
        long orderB = nextOrderId();
        long candidateA = insertRecentCandidate(orderA);
        long candidateB = insertRecentCandidate(orderB);
        Long completedSnapshotId = runCompleted();
        Long failedSnapshotId = runFailed();
        insertFailure(completedSnapshotId, candidateA, orderA, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(failedSnapshotId, candidateA, orderA, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(failedSnapshotId, candidateB, orderB, PayoutFailureReason.DEDUCTION_MISSING);

        // when
        Map<Long, Long> sut = payoutQueryRepository.countFailedRunsByOrder();

        // then
        assertThat(sut).containsEntry(orderA, 1L).doesNotContainKey(orderB);
    }

    @Test
    void 이미_정산된_후보의_실패는_세지_않는다() throws Exception {
        // given: 대기가 지난 후보는 실제 Job이 정산하고, 대기 중인 같은 주문의 후보는 정산되지 않음
        long order = nextOrderId();
        long settledCandidate = insertCandidate(order, LocalDateTime.now().minusDays(20));
        long unsettledCandidate = insertRecentCandidate(order);
        // 정산된 후보의 실패만 있는 다른 주문
        long otherOrder = nextOrderId();
        long otherSettledCandidate = insertCandidate(otherOrder, LocalDateTime.now().minusDays(20));
        Long snapshotId = runCompleted();
        assertThat(payoutRepository.findBySnapshotId(snapshotId)).as("준비: 실행이 대기 지난 후보를 정산함").isNotEmpty();
        insertFailure(snapshotId, settledCandidate, order, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(snapshotId, unsettledCandidate, order, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(snapshotId, otherSettledCandidate, otherOrder, PayoutFailureReason.DEDUCTION_MISSING);

        // when
        Map<Long, Long> sut = payoutQueryRepository.countFailedRunsByOrder();

        // then: 주문의 미정산 후보 실패만 셈. 정산된 후보의 실패만 있는 주문은 결과에 없음
        assertThat(sut).containsEntry(order, 1L).doesNotContainKey(otherOrder);
    }

    @Test
    void 다시_넣을_주문의_후보는_상한_전의_미정산_후보만_잠긴_주문은_빼고_고른다() throws Exception {
        // given: 상한 이전·이후 후보를 가진 주문 X, 금액 불일치로 잠긴 주문 Y, 넘기지 않을 주문 Z
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // 실제 Job은 오래된 후보를 정산해 버리므로, 후보를 넣기 전에 실행부터 함 (실패 기록을 붙일 스냅샷만 필요)
        Long snapshotId = runCompleted();
        long orderX = nextOrderId();
        long orderY = nextOrderId();
        long orderZ = nextOrderId();
        long xOld = insertCandidate(orderX, to.minusYears(1));   // 아주 오래된 후보: 하한이 없어 포함
        long xBefore = insertCandidate(orderX, to.minusSeconds(1));
        insertCandidate(orderX, to);                              // 상한 정각 → 제외 (<)
        long yBefore = insertCandidate(orderY, to.minusDays(1));
        insertCandidate(orderZ, to.minusDays(1));
        insertFailure(snapshotId, yBefore, orderY, PayoutFailureReason.AMOUNT_MISMATCH);   // 주문을 잠그는 사유

        // when
        List<PayoutCandidateItem> sut = payoutQueryRepository.findUnsettledCandidatesOfOrdersBefore(
                List.of(orderX, orderY), to);

        // then: id 오름차순. 주문 Z는 넘기지 않았고 잠긴 Y는 빠짐
        assertThat(sut).extracting(PayoutCandidateItem::getId).containsExactly(xOld, xBefore);
        // 주문을 하나도 안 넘기면 질의하지 않고 빈 목록
        assertThat(payoutQueryRepository.findUnsettledCandidatesOfOrdersBefore(List.of(), to)).isEmpty();
    }

    @Test
    void 결제_기록이_늦게_와도_3번_안이면_다음_실행에서_정산된다() throws Exception {
        // given: 정산 대기 중인 후보의 주문이 성공한 실행 2번에서 실패 (MAX_FAILED_RUNS=3 미만)
        long order = nextOrderId();
        long candidate = insertRecentCandidate(order);
        insertFailure(runCompleted(), candidate, order, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(runCompleted(), candidate, order, PayoutFailureReason.DEDUCTION_MISSING);

        // when
        List<Long> sut = snapshotCandidateIds();

        // then: 멈추지 않아 스냅샷에 들어감
        assertThat(sut).containsExactly(candidate);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 성공한_실행에서_3번_실패한_주문은_다음_스냅샷에서_빠지고_멈춘_주문_수가_WARN으로_남는다(CapturedOutput output) throws Exception {
        // given: 주문 A는 성공한 실행 3번에서 실패(후보 2건), 주문 B는 실패 없음
        long orderA = nextOrderId();
        long orderB = nextOrderId();
        long candidateA1 = insertRecentCandidate(orderA);
        insertRecentCandidate(orderA);
        long candidateB = insertRecentCandidate(orderB);
        insertFailure(runCompleted(), candidateA1, orderA, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(runCompleted(), candidateA1, orderA, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(runCompleted(), candidateA1, orderA, PayoutFailureReason.DEDUCTION_MISSING);

        // when
        Long snapshotId = createSnapshotNow();

        // then: 주문 A의 후보는 모두 빠지고 B만 남음. 로그의 제외 주문 수는 후보 수(2)가 아니라 주문 수(1)
        assertThat(candidateIdsOf(snapshotId)).containsExactly(candidateB);
        assertThat(output.getAll()).contains("3번 실패한 주문이라 스냅샷에서 제외. snapshotId=" + snapshotId + ", 제외 주문 수=1");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 멈춘_주문이_없으면_WARN을_남기지_않는다(CapturedOutput output) throws Exception {
        // given: 1번만 실패한 주문
        long order = nextOrderId();
        long candidate = insertRecentCandidate(order);
        insertFailure(runCompleted(), candidate, order, PayoutFailureReason.DEDUCTION_MISSING);

        // when
        createSnapshotNow();

        // then
        assertThat(output.getAll()).doesNotContain("3번 실패한 주문이라 스냅샷에서 제외");
    }

    @Test
    void 세번을_못_채우고_조회_기간_밖으로_밀린_실패_주문은_다시_넣는다() throws Exception {
        // given: 실행을 먼저 하고(실제 Job이 오래된 후보를 정산하지 않게), 조회 기간 밖(30일 전) 후보를 둠
        Long runSnapshotId = runCompleted();
        long failedOrder = nextOrderId();
        long cleanOrder = nextOrderId();
        long failedCandidate = insertCandidate(failedOrder, LocalDateTime.now().minusDays(30));
        insertCandidate(cleanOrder, LocalDateTime.now().minusDays(30));
        insertFailure(runSnapshotId, failedCandidate, failedOrder, PayoutFailureReason.DEDUCTION_MISSING);

        // when
        List<Long> sut = snapshotCandidateIds();

        // then: 1번 실패한 주문의 후보만 기간 밖이어도 들어감. 실패 없는 주문은 그대로 기간 밖
        assertThat(sut).containsExactly(failedCandidate);
    }

    @Test
    void 실패로_끝난_실행만_겪은_주문은_다시_넣지_않는다() throws Exception {
        // given: FAILED로 끝난 실행에서만 실패한 주문의 기간 밖 후보
        Long failedSnapshotId = runFailed();
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(failedSnapshotId, candidate, order, PayoutFailureReason.DEDUCTION_MISSING);

        // when
        List<Long> sut = snapshotCandidateIds();

        // then: 횟수에 안 세므로 다시 넣지 않음
        assertThat(sut).isEmpty();
    }

    @Test
    void 멈춘_주문은_뒤늦게_들어온_같은_주문의_후보도_함께_빠진다() throws Exception {
        // given: 주문의 첫 후보가 3번 실패해 멈춘 뒤, 같은 주문의 새 후보가 들어옴
        long order = nextOrderId();
        long first = insertRecentCandidate(order);
        insertFailure(runCompleted(), first, order, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(runCompleted(), first, order, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(runCompleted(), first, order, PayoutFailureReason.DEDUCTION_MISSING);
        insertRecentCandidate(order);

        // when
        List<Long> sut = snapshotCandidateIds();

        // then: 주문 단위로 멈춤
        assertThat(sut).isEmpty();
    }

    @Test
    void 한_실행_안에서_다른_판매자의_통과한_상품은_정산된다() throws Exception {
        // given: 한 주문에 판매자 10(오래된 후보)·판매자 20(대기 중 후보). 실제 Job이 판매자 10 몫을 정산함
        // 판매자 20의 실패는 SQL로 넣음: 실제 Job으로 한 판매자만 실패시키려면 아직 없는 스냅샷 ID에 막는 행이 필요해 만들 수 없음
        long order = nextOrderId();
        long passed = insertCandidate(order, LocalDateTime.now().minusDays(20), 10L);
        long failed = insertCandidate(order, LocalDateTime.now(), 20L);
        Long firstSnapshotId = runCompleted();
        assertThat(payoutRepository.findBySnapshotId(firstSnapshotId)).as("준비: 판매자 10 몫이 정산됨").isNotEmpty();
        insertFailure(firstSnapshotId, failed, order, PayoutFailureReason.UNEXPECTED_ERROR);
        insertFailure(runCompleted(), failed, order, PayoutFailureReason.UNEXPECTED_ERROR);
        insertFailure(runCompleted(), failed, order, PayoutFailureReason.UNEXPECTED_ERROR);

        // when
        List<Long> sut = snapshotCandidateIds();

        // then: 주문이 멈춰 판매자 20의 후보는 빠지고, 이미 정산된 판매자 10의 후보는 미정산이 아니라 들어가지 않으며 정산 결과는 그대로
        assertThat(sut).doesNotContain(failed, passed);
        assertThat(payoutRepository.findBySnapshotId(firstSnapshotId)).isNotEmpty();
    }

    @Test
    void 잠긴_주문은_실패_횟수가_1번이어도_다시_넣지_않는다() throws Exception {
        // given: 금액 불일치로 잠긴 주문(1번 실패)의 기간 밖 후보
        Long runSnapshotId = runCompleted();
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(runSnapshotId, candidate, order, PayoutFailureReason.AMOUNT_MISMATCH);

        // when
        List<Long> sut = snapshotCandidateIds();

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 기간_안_후보와_다시_넣는_후보가_겹쳐도_한_번만_들어간다() throws Exception {
        // given: 1번 실패한 주문의 후보가 조회 기간 안에도 있고 다시 넣는 조회에도 잡힘
        long order = nextOrderId();
        long candidate = insertRecentCandidate(order);
        insertFailure(runCompleted(), candidate, order, PayoutFailureReason.DEDUCTION_MISSING);

        // when
        List<Long> sut = snapshotCandidateIds();

        // then
        assertThat(sut).containsExactly(candidate);
    }

    // ---------- 도우미 ----------

    // 실제 정산 Job을 돌려 COMPLETED 실행을 만들고 그 실행의 스냅샷 ID를 돌려줌
    private Long runCompleted() throws Exception {
        JobExecution execution = payoutJobRunner.runScheduled();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return snapshotIdOf(execution);
    }

    // 실제 Job을 돌린 뒤 실행 기록 상태를 FAILED로 바꿈 (클래스 설명 참고)
    private Long runFailed() throws Exception {
        JobExecution execution = payoutJobRunner.runScheduled();
        execution.setStatus(BatchStatus.FAILED);
        jobRepository.update(execution);
        return snapshotIdOf(execution);
    }

    // Job 실행 1회 = 스냅샷 1건이라 실행 ID로 스냅샷을 찾음 (JPA 조회)
    private Long snapshotIdOf(JobExecution execution) {
        for (PayoutSnapshot snapshot : snapshotRepository.findAll()) {
            if (snapshot.getJobExecutionId().equals(execution.getId())) {
                return snapshot.getId();
            }
        }
        throw new IllegalStateException("실행에 해당하는 스냅샷이 없습니다. executionId=" + execution.getId());
    }

    // 정산 대기 중이라 실제 Job이 대상으로 잡지 않는 후보 (생성 시각 = 지금)
    private long insertRecentCandidate(long orderId) {
        return insertCandidate(orderId, LocalDateTime.now());
    }

    // 정산후보를 원하는 created_at으로 직접 저장하고 id를 돌려줌 (저장 시 Auditing이 시각을 덮어써서 SQL로 넣음)
    // 주문마다 지갑 차감 기록을 1건 넣음: 실제 Job이 대기 지난 후보를 정산할 수 있게 함 (없으면 DEDUCTION_MISSING으로 빠짐)
    private long insertCandidate(long orderId, LocalDateTime createdAt) {
        return insertCandidate(orderId, createdAt, 10L);
    }

    private long insertCandidate(long orderId, LocalDateTime createdAt, long sellerId) {
        long orderItemId = ORDER_ITEM_ID_SEQ.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                PayoutEventType.SALE_AMOUNT.name(), orderId, orderItemId, sellerId, 1_000L, createdAt, createdAt);
        if (!walletDeductionRepository.existsByOrderId(orderId)) {
            walletDeductionRepository.save(PayoutWalletDeduction.create(orderId, 1L, 1_000_000L, LocalDateTime.now()));
        }
        for (PayoutCandidateItem candidate : candidateRepository.findAllByOrderId(orderId)) {
            if (candidate.getOrderItemId().equals(orderItemId)) {
                return candidate.getId();
            }
        }
        throw new IllegalStateException("정산후보가 없습니다. orderId=" + orderId);
    }

    private void insertFailure(Long snapshotId, long candidateId, long orderId, PayoutFailureReason reason) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_failure (snapshot_id, candidate_id, order_id, order_item_id, seller_id, reason, detail, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 10, ?, ?, ?, ?)",
                snapshotId, candidateId, orderId, reason.name(), "테스트", now, now);
    }

    // 스냅샷을 직접 만들고(가짜 실행 ID라 실패 횟수에 영향 없음) 들어간 후보 ID를 돌려줌
    private List<Long> snapshotCandidateIds() {
        return candidateIdsOf(createSnapshotNow());
    }

    // 조회 기간 [오늘-1일, 내일)로 스냅샷 생성: 지금 만든 후보는 기간 안, 30일 전 후보는 기간 밖
    private Long createSnapshotNow() {
        LocalDateTime now = LocalDateTime.now();
        return payoutService.createSnapshot(SNAPSHOT_EXECUTION_ID_SEQ.incrementAndGet(), now.minusDays(1), now.plusDays(1),
                PayoutRunType.SCHEDULED);
    }

    private List<Long> candidateIdsOf(Long snapshotId) {
        List<Long> ids = new ArrayList<>();
        for (PayoutSnapshotItem item : snapshotItemRepository.findBySnapshotId(snapshotId)) {
            ids.add(item.getCandidateId());
        }
        return ids;
    }

    private long nextOrderId() {
        return ORDER_ID_SEQ.incrementAndGet();
    }

    // 정산·draft·스냅샷은 전부, 후보·차감 기록은 이 클래스 번호대만 지움 (FK 때문에 자식 테이블 먼저)
    // 정산 테이블을 전부 비우는 이유: 다른 통합 테스트가 남긴 정산 흔적이 실제 Job 실행 결과에 섞이지 않게 하려는 것 (다른 통합 테스트와 같음)
    // (리포지토리에는 삭제 기능이 없으므로 정리용으로만 SQL 직접 실행)
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
