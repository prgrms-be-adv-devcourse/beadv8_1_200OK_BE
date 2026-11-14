package com.ok.payout;

import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.out.persistence.PayoutBlockedOrderCauseJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutBlockedOrderJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutBlockedOrderRefreshJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutCandidateItemJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutSnapshotJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.application.port.in.PayoutBlockedOrderUseCase;
import com.ok.payout.application.port.in.PayoutFailureResolutionUseCase;
import com.ok.payout.application.port.in.PayoutOrderResolveCommand;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.domain.PayoutBlockedOrder;
import com.ok.payout.domain.PayoutBlockedOrderCause;
import com.ok.payout.domain.PayoutBlockedOrderRefresh;
import com.ok.payout.domain.PayoutBlockedStatus;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트: 막힌 주문 집계 표 (#582)
// - 실행(COMPLETED)은 실제 정산 Job(PayoutJobRunner.runScheduled)으로 만든다. Job이 끝날 때 리스너가 표를 갱신한다
// - 멈춘 주문은 지갑 결제 기록 없는 후보로 실제 Job이 스스로 "결제 기록 없음"으로 실패하게 해서 만든다 (실제 흐름 그대로)
// - 잠긴 주문·사유가 섞인 이력은 실행을 먼저 만들고, 그 스냅샷에 실패 기록을 SQL로 넣은 뒤 갱신(입력 포트)을 직접 부른다
//   (실제로는 그 실행의 Job 끝에 갱신되지만, 실패 기록을 Job 밖에서 넣었으므로 갱신을 한 번 더 부름)
// - 결과 확인은 JPA 리포지토리로 한다. JDBC는 데이터 준비·정리에만 씀
class PayoutBlockedOrderIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문 ID 번호대 (다른 통합 테스트와 겹치지 않게 5_300_000~5_400_000)
    private static final long ORDER_ID_START = 5_300_000L;
    private static final long ORDER_ID_END = 5_400_000L;
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(ORDER_ID_START);
    // 주문항목 ID 번호대: 주문 ID와 겹치지 않게 따로 발급
    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(17_000_000L);

    private static final long ADMIN_ID = 1L;
    private static final long SELLER_ID = 10L;
    private static final String HELD_DETAIL = "[함께 보류] 같은 주문의 결과 검증 불일치 (원인 후보 [1])";

    @Autowired
    private PayoutBlockedOrderUseCase blockedOrderUseCase;

    @Autowired
    private PayoutFailureResolutionUseCase resolutionUseCase;

    @Autowired
    private PayoutQueryRepository payoutQueryRepository;

    @Autowired
    private PayoutBlockedOrderJpaRepository blockedOrderRepository;

    @Autowired
    private PayoutBlockedOrderCauseJpaRepository causeRepository;

    @Autowired
    private PayoutBlockedOrderRefreshJpaRepository refreshRepository;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PayoutSnapshotJpaRepository snapshotRepository;

    @Autowired
    private PayoutCandidateItemJpaRepository candidateRepository;

    @Autowired
    private PayoutWalletDeductionJpaRepository walletDeductionRepository;

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

    // ---------- Job 끝 갱신 ----------

    @Test
    void 세번_실패해_멈춘_주문은_세번째_Job이_끝날_때_STOPPED로_들어간다() throws Exception {
        // given: 결제 기록이 없어 실제 Job마다 "결제 기록 없음"으로 실패하는 주문
        long order = nextOrderId();
        insertCandidateWithoutDeduction(order, LocalDateTime.now().minusDays(30));

        // when & then: 2번째까지는 아직 다시 검사 중이라 없음, 3번째 Job이 끝나면 들어감
        runCompleted(2);
        assertThat(blockedOrderRepository.findById(order)).isEmpty();
        runCompleted();

        // then: Job 끝 리스너가 방금 끝난 실행까지 세어 멈춤으로 판정 (실행 상태가 저장된 뒤 afterJob이 불림)
        PayoutBlockedOrder sut = blockedOrderRepository.findById(order).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutBlockedStatus.STOPPED);
            softly.assertThat(sut.getFailedRunCount()).isEqualTo(3);
            softly.assertThat(sut.getLastFailedAt()).isNotNull();
        });
        // 실행마다 같은 실패가 쌓였지만 원인은 1줄
        assertThat(causeRepository.findAllByOrderId(order))
                .extracting(PayoutBlockedOrderCause::getReason, PayoutBlockedOrderCause::getEventType)
                .containsExactly(tuple(PayoutFailureReason.DEDUCTION_MISSING, PayoutEventType.SALE_AMOUNT));
    }

    @Test
    void 잠긴_주문은_실패_횟수가_1이어도_LOCKED로_들어간다() throws Exception {
        // given: 성공한 실행에서 결과 검증 불일치 1번
        Long run = runCompleted();
        long order = nextOrderId();
        long candidate = insertCandidate(order);
        insertFailure(run, candidate, order, null, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 금액 다름");

        // when
        blockedOrderUseCase.refresh();

        // then
        assertThat(blockedOrderRepository.findById(order))
                .get().extracting(PayoutBlockedOrder::getStatus, PayoutBlockedOrder::getFailedRunCount)
                .containsExactly(PayoutBlockedStatus.LOCKED, 1);
    }

    @Test
    void 실패한_실행에서만_잠긴_주문은_횟수_0으로_LOCKED가_된다() throws Exception {
        // given: 실패로 끝난 실행의 결과 검증 불일치 (잠금은 실행 결과를 보지 않고, 횟수는 성공한 실행만 셈)
        Long run = runFailed();
        long order = nextOrderId();
        long candidate = insertCandidate(order);
        insertFailure(run, candidate, order, null, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 금액 다름");

        // when
        blockedOrderUseCase.refresh();

        // then
        assertThat(blockedOrderRepository.findById(order))
                .get().extracting(PayoutBlockedOrder::getStatus, PayoutBlockedOrder::getFailedRunCount)
                .containsExactly(PayoutBlockedStatus.LOCKED, 0);
    }

    @Test
    void 잠기지_않고_1에서_2번_실패한_주문은_들어가지_않는다() throws Exception {
        // given: 초과 청구 2번 (잠그지 않는 사유, 아직 다시 검사 중)
        List<Long> runs = runCompleted(2);
        long order = nextOrderId();
        long candidate = insertCandidate(order);
        insertFailure(runs.get(0), candidate, order, null, PayoutFailureReason.AMOUNT_MISMATCH, "초과 청구");
        insertFailure(runs.get(1), candidate, order, null, PayoutFailureReason.AMOUNT_MISMATCH, "초과 청구");

        // when
        blockedOrderUseCase.refresh();

        // then
        assertThat(blockedOrderRepository.findById(order)).isEmpty();
    }

    @Test
    void 잠김이면서_멈춘_주문은_LOCKED_한_줄이다() throws Exception {
        // given: 초과 청구 2번 + 결과 검증 불일치 1번 = 성공한 실행 3번
        List<Long> runs = runCompleted(3);
        long order = nextOrderId();
        long candidate = insertCandidate(order);
        insertFailure(runs.get(0), candidate, order, null, PayoutFailureReason.AMOUNT_MISMATCH, "초과 청구");
        insertFailure(runs.get(1), candidate, order, null, PayoutFailureReason.AMOUNT_MISMATCH, "초과 청구");
        insertFailure(runs.get(2), candidate, order, null, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 금액 다름");

        // when
        blockedOrderUseCase.refresh();

        // then: 잠김은 다시 검사해도 안 풀려 사람이 꼭 봐야 하므로 LOCKED
        assertThat(blockedOrderRepository.findById(order))
                .get().extracting(PayoutBlockedOrder::getStatus, PayoutBlockedOrder::getFailedRunCount)
                .containsExactly(PayoutBlockedStatus.LOCKED, 3);
    }

    // ---------- 해결 ----------

    @Test
    void 해결하면_RESOLVED가_되고_다음_갱신에서_지워진다() throws Exception {
        // given: 표에 들어간 잠긴 주문
        long order = givenLockedOrderInTable();

        // when: 해결
        resolve(order);

        // then: 바로 RESOLVED로 보이고, 다음 갱신이 원본(해결됨)으로 판정해 지움 (원인 줄도 함께)
        assertThat(blockedOrderRepository.findById(order))
                .get().extracting(PayoutBlockedOrder::getStatus).isEqualTo(PayoutBlockedStatus.RESOLVED);
        blockedOrderUseCase.refresh();
        assertThat(blockedOrderRepository.findById(order)).isEmpty();
        assertThat(causeRepository.findAllByOrderId(order)).isEmpty();
    }

    @Test
    void 해결_뒤_다시_잠기면_다음_갱신에서_다시_LOCKED가_된다() throws Exception {
        // given: 해결해 RESOLVED가 된 뒤, 다시 검사에서 또 결과 검증 불일치
        long order = givenLockedOrderInTable();
        resolve(order);
        Long run = runCompleted();
        long candidate = candidateRepository.findAllByOrderId(order).get(0).getId();
        insertFailure(run, candidate, order, null, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 또 다름");

        // when
        blockedOrderUseCase.refresh();

        // then: 표에 있는 주문이라 다시 판정되고, 해결 뒤 생긴 실패로 다시 잠김
        assertThat(blockedOrderRepository.findById(order))
                .get().extracting(PayoutBlockedOrder::getStatus).isEqualTo(PayoutBlockedStatus.LOCKED);
        assertThat(causeRepository.findAllByOrderId(order))
                .extracting(PayoutBlockedOrderCause::getDetail).containsExactly("[원인] 또 다름");
    }

    // ---------- 원인 ----------

    @Test
    void 원인은_함께_보류를_빼고_같은_기록은_한_번만_남긴다() throws Exception {
        // given: 상품 1개(대금·수수료)가 수수료 불일치로 2번 기록됨, 배송비는 함께 보류(ORDER_HELD),
        //   같은 주문의 결과 검증 "[함께 보류]" 기록도 있음
        List<Long> runs = runCompleted(2);
        long order = nextOrderId();
        long orderItem = ORDER_ITEM_ID_SEQ.incrementAndGet();
        long amount = insertCandidateOf(order, PayoutEventType.SALE_AMOUNT, orderItem, 10_000L);
        long fee = insertCandidateOf(order, PayoutEventType.SALE_FEE, orderItem, 300L);
        long shipping = insertCandidateOf(order, PayoutEventType.SALE_SHIPPING_FEE, null, 3_000L);
        for (Long run : runs) {
            insertFailure(run, amount, order, orderItem, PayoutFailureReason.FEE_MISMATCH, "수수료 불일치");
            insertFailure(run, fee, order, orderItem, PayoutFailureReason.FEE_MISMATCH, "수수료 불일치");
        }
        insertFailure(runs.get(0), shipping, order, null, PayoutFailureReason.ORDER_HELD, "같은 주문의 수수료 불일치로 보류");
        insertFailure(runs.get(1), shipping, order, null, PayoutFailureReason.VERIFY_MISMATCH, HELD_DETAIL);

        // when
        blockedOrderUseCase.refresh();

        // then: 원인은 대금·수수료 FEE_MISMATCH 2줄 (실행 2번이어도 1번씩, 배송비 함께 보류는 빠짐)
        assertThat(causeRepository.findAllByOrderId(order))
                .extracting(PayoutBlockedOrderCause::getEventType, PayoutBlockedOrderCause::getReason,
                        PayoutBlockedOrderCause::getOrderItemId)
                .containsExactlyInAnyOrder(
                        tuple(PayoutEventType.SALE_AMOUNT, PayoutFailureReason.FEE_MISMATCH, orderItem),
                        tuple(PayoutEventType.SALE_FEE, PayoutFailureReason.FEE_MISMATCH, orderItem));
    }

    // ---------- 반영 표시 ----------

    @Test
    void 가장_큰_실패_기록_ID가_반영_표시보다_작아도_갱신이_실패하지_않고_표시를_그대로_둔다() {
        // given: 사람이 최근 실패 기록을 DB에서 지워 가장 큰 ID가 반영 표시보다 작아진 상황
        jdbcTemplate.update("UPDATE payout_blocked_order_refresh SET last_failure_id = ?", Long.MAX_VALUE - 1);

        // when
        blockedOrderUseCase.refresh();

        // then: 반영 표시를 뒤로 돌리지 않음 (돌리려다 예외가 나면 그 뒤 모든 Job의 갱신이 매번 실패함)
        assertThat(refreshRepository.findById(PayoutBlockedOrderRefresh.SINGLE_ROW_ID))
                .get().extracting(PayoutBlockedOrderRefresh::getLastFailureId).isEqualTo(Long.MAX_VALUE - 1);
    }

    @Test
    void 반영_표시_앞의_실패_기록은_다시_읽지_않는다() throws Exception {
        // given: 이미 반영했다고 표시된 실패 기록 (표에는 없음)
        Long run = runCompleted();
        long before = nextOrderId();
        insertFailure(run, insertCandidate(before), before, null, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 앞");
        jdbcTemplate.update("UPDATE payout_blocked_order_refresh SET last_failure_id = (SELECT MAX(id) FROM payout_failure)");
        long after = nextOrderId();
        insertFailure(run, insertCandidate(after), after, null, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 뒤");

        // when
        blockedOrderUseCase.refresh();

        // then: 반영 표시 뒤의 실패만 읽어 운영 기간 전체를 매번 읽지 않음 (앞 주문은 범위 맞추기 #583 몫)
        assertThat(blockedOrderRepository.findById(before)).isEmpty();
        assertThat(blockedOrderRepository.findById(after)).isPresent();
        assertThat(refreshRepository.findById(PayoutBlockedOrderRefresh.SINGLE_ROW_ID))
                .get().extracting(PayoutBlockedOrderRefresh::getLastFailureId)
                .isEqualTo(payoutQueryRepository.findMaxFailureId());
    }

    @Test
    void 갱신을_놓친_실패도_다음_갱신이_이어서_잡는다() throws Exception {
        // given: 두 실행에서 생긴 잠김. 첫 실패 뒤 갱신이 없었음 (갱신 실패나 서버 다운으로 놓친 상황)
        List<Long> runs = runCompleted(2);
        long missed = nextOrderId();
        insertFailure(runs.get(0), insertCandidate(missed), missed, null, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 놓침");
        long latest = nextOrderId();
        insertFailure(runs.get(1), insertCandidate(latest), latest, null, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 최근");

        // when: 다음 갱신 한 번
        blockedOrderUseCase.refresh();

        // then: 잠긴 주문은 다음 스냅샷에 안 들어가 새 실패가 없지만, 반영 표시 뒤라 둘 다 들어감
        assertThat(blockedOrderRepository.findById(missed)).isPresent();
        assertThat(blockedOrderRepository.findById(latest)).isPresent();
    }

    // ---------- 해결 API와 같은 판정 ----------

    @Test
    void 표에_있는_주문은_해결_API가_해결할_것이_있다고_보는_주문과_같다() throws Exception {
        // given: 7가지 경우
        List<Long> completed = runCompleted(3);
        List<Long> failed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            failed.add(runFailed());
        }
        long locked = givenOrderWithFailures(List.of(completed.get(0)), PayoutFailureReason.VERIFY_MISMATCH);
        long stopped = givenOrderWithFailures(completed, PayoutFailureReason.AMOUNT_MISMATCH);
        long lockedAndStopped = givenOrderWithFailures(completed, PayoutFailureReason.FEE_MISMATCH);
        long retrying = givenOrderWithFailures(completed.subList(0, 2), PayoutFailureReason.AMOUNT_MISMATCH);
        long failedRunsOnly = givenOrderWithFailures(failed, PayoutFailureReason.AMOUNT_MISMATCH);
        long lockedInFailedRun = givenOrderWithFailures(List.of(failed.get(0)), PayoutFailureReason.VERIFY_MISMATCH);
        long resolved = givenOrderWithFailures(List.of(completed.get(0)), PayoutFailureReason.VERIFY_MISMATCH);
        resolve(resolved);

        // when
        blockedOrderUseCase.refresh();

        // then: 해결 API의 판정(PayoutFailureResolutionService: 잠김 또는 멈춤이면 해결할 것 있음)과 표에 있는지가 같음
        Map<Long, Boolean> expected = Map.of(locked, true, stopped, true, lockedAndStopped, true, retrying, false,
                failedRunsOnly, false, lockedInFailedRun, true, resolved, false);
        for (Map.Entry<Long, Boolean> entry : expected.entrySet()) {
            long order = entry.getKey();
            boolean resolvable = payoutQueryRepository.isOrderLocked(order)
                    || PayoutPolicy.isStopped(payoutQueryRepository.countFailedRunsOfOrder(order));
            assertThat(resolvable).as("해결 API 판정 orderId=%s", order).isEqualTo(entry.getValue());
            assertThat(blockedOrderRepository.findById(order).isPresent()).as("표 orderId=%s", order).isEqualTo(resolvable);
        }
    }

    // ---------- 도우미 ----------

    private long givenLockedOrderInTable() throws Exception {
        Long run = runCompleted();
        long order = nextOrderId();
        insertFailure(run, insertCandidate(order), order, null, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 금액 다름");
        blockedOrderUseCase.refresh();
        assertThat(blockedOrderRepository.findById(order)).isPresent();
        return order;
    }

    // 실행마다 같은 후보에 같은 사유로 실패 기록을 남긴 주문
    private long givenOrderWithFailures(List<Long> snapshotIds, PayoutFailureReason reason) {
        long order = nextOrderId();
        long candidate = insertCandidate(order);
        for (Long snapshotId : snapshotIds) {
            insertFailure(snapshotId, candidate, order, null, reason, "테스트");
        }
        return order;
    }

    private void resolve(long orderId) {
        resolutionUseCase.resolveOrder(new PayoutOrderResolveCommand(orderId, ADMIN_ID, "다시 처리"));
    }

    private List<Long> runCompleted(int times) throws Exception {
        List<Long> snapshotIds = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            snapshotIds.add(runCompleted());
        }
        return snapshotIds;
    }

    private Long runCompleted() throws Exception {
        JobExecution execution = payoutJobRunner.runScheduled();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return snapshotIdOf(execution);
    }

    // 실행 기록을 FAILED로 바꿔 "실패로 끝난 실행"을 만듦 (Job 끝 갱신은 이미 COMPLETED로 돈 뒤라, 이 실행의 실패는 부르는 쪽이 갱신을 다시 부름)
    private Long runFailed() throws Exception {
        JobExecution execution = payoutJobRunner.runScheduled();
        execution.setStatus(BatchStatus.FAILED);
        jobRepository.update(execution);
        return snapshotIdOf(execution);
    }

    private Long snapshotIdOf(JobExecution execution) {
        for (PayoutSnapshot snapshot : snapshotRepository.findAll()) {
            if (snapshot.getJobExecutionId().equals(execution.getId())) {
                return snapshot.getId();
            }
        }
        throw new IllegalStateException("실행에 해당하는 스냅샷이 없습니다. executionId=" + execution.getId());
    }

    // 결제 기록이 있는 30일 전 대금 후보
    private long insertCandidate(long orderId) {
        long candidateId = insertCandidateWithoutDeduction(orderId, LocalDateTime.now().minusDays(30));
        givenDeduction(orderId);
        return candidateId;
    }

    private long insertCandidateWithoutDeduction(long orderId, LocalDateTime createdAt) {
        return insertCandidateOf(orderId, PayoutEventType.SALE_AMOUNT, ORDER_ITEM_ID_SEQ.incrementAndGet(), 1_000L, createdAt);
    }

    private long insertCandidateOf(long orderId, PayoutEventType eventType, Long orderItemId, long amount) {
        return insertCandidateOf(orderId, eventType, orderItemId, amount, LocalDateTime.now().minusDays(30));
    }

    private long insertCandidateOf(long orderId, PayoutEventType eventType, Long orderItemId, long amount,
                                   LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderId, orderItemId, SELLER_ID, amount, createdAt, createdAt);
        for (PayoutCandidateItem candidate : candidateRepository.findAllByOrderId(orderId)) {
            if (candidate.getEventType() == eventType && Objects.equals(candidate.getOrderItemId(), orderItemId)) {
                return candidate.getId();
            }
        }
        throw new IllegalStateException("정산후보가 없습니다. orderId=" + orderId);
    }

    private void givenDeduction(long orderId) {
        if (!walletDeductionRepository.existsByOrderId(orderId)) {
            walletDeductionRepository.save(PayoutWalletDeduction.create(orderId, 1L, 1_000_000L, LocalDateTime.now()));
        }
    }

    private void insertFailure(Long snapshotId, long candidateId, long orderId, Long orderItemId,
                               PayoutFailureReason reason, String detail) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_failure (snapshot_id, candidate_id, order_id, order_item_id, seller_id, reason, detail, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshotId, candidateId, orderId, orderItemId, SELLER_ID, reason.name(), detail, now, now);
    }

    private long nextOrderId() {
        return ORDER_ID_SEQ.incrementAndGet();
    }

    // 막힌 주문 표 묶음은 원인 → 막힌 주문 순서로 지우고(외래키), 반영 표시를 0으로 되돌림 (마이그레이션 직후와 같게)
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM payout_blocked_order_cause");
        jdbcTemplate.update("DELETE FROM payout_blocked_order");
        jdbcTemplate.update("UPDATE payout_blocked_order_refresh SET last_failure_id = 0");
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
