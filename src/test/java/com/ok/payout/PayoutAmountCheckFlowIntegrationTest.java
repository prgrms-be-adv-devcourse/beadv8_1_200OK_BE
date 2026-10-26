package com.ok.payout;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.payout.repository.PayoutFailureRepository;
import com.ok.payout.repository.PayoutRepository;
import com.ok.payout.repository.PayoutSnapshotItemRepository;
import com.ok.payout.repository.PayoutSnapshotRepository;
import com.ok.payout.repository.PayoutWalletDeductionRepository;
import com.ok.payout.service.PayoutJobRunner;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 금액 검사(#195)를 실제 이벤트 경로로 확인
// PayoutSettleIntegrationTest는 후보·차감 기록을 SQL로 직접 넣지만, 여기서는 주문·지갑이 보내는 이벤트를 그대로 발행해
// 정산 리스너가 만든 후보(수수료 계산·배송비 1건 규칙 포함)와 차감 기록으로 Job을 돌린다
// → "정상 주문은 금액 검사에 걸리지 않는다"는 전제를 실제 후보 생성 규칙으로 확인
//   (차감액 = 주문 결제 금액 = 상품 금액 합 + 판매자 묶음 배송비 합, 후보 합계 = 상품 금액 합 + 배송비 후보)
// - 리스너(@ApplicationModuleListener)는 커밋 후 비동기로 실행되므로 트랜잭션 안에서 발행하고 결과를 await()로 기다린다
// - 정산은 생성 15일 뒤가 대상이라 후보가 만들어진 뒤 created_at을 과거로 옮긴다 (PayoutDepositIntegrationTest와 같은 방식)
// - 정산·스냅샷·실패 기록은 "마지막 정산일"이 조회 범위 계산에 쓰이므로 테스트 전후로 전부 비운다
class PayoutAmountCheckFlowIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // 이 클래스 전용 번호대 (다른 정산 테스트는 4_000_000~, 8_000_000~ 등을 사용)
    private static final long ORDER_ID_START = 3_000_000L;
    private static final long ORDER_ID_END = 3_100_000L;
    private static final long NORMAL_ORDER_ID = 3_000_001L;
    private static final long TAMPERED_ORDER_ID = 3_000_002L;

    private static final long SELLER_A = 3_001L;
    private static final long SELLER_B = 3_002L;
    private static final long SELLER_C = 3_003L;
    private static final long BUYER_ID = 3_100L;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private PayoutCandidateItemRepository candidateRepository;

    @Autowired
    private PayoutWalletDeductionRepository deductionRepository;

    @Autowired
    private PayoutSnapshotRepository snapshotRepository;

    @Autowired
    private PayoutSnapshotItemRepository snapshotItemRepository;

    @Autowired
    private PayoutFailureRepository failureRepository;

    @Autowired
    private PayoutRepository payoutRepository;

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
    void 이벤트로_들어온_정상_주문은_금액_검사를_통과하고_정산된다() throws Exception {
        // given: 판매자 A(10,000원 + 5,000원, 묶음 배송비 3,000원)와 판매자 B(7,777원, 무료배송)가 함께 든 주문
        // 차감액 = 상품 22,777 + 배송비 3,000 = 25,777 (후보 합계와 같아 경계값에서 통과해야 함)
        givenPayment(NORMAL_ORDER_ID, 25_777L);
        givenConfirmed(NORMAL_ORDER_ID, 3_000_101L, SELLER_A, 10_000L, 3_000L);
        givenConfirmed(NORMAL_ORDER_ID, 3_000_102L, SELLER_A, 5_000L, 3_000L);   // 같은 묶음이라 배송비 후보는 만들지 않음
        givenConfirmed(NORMAL_ORDER_ID, 3_000_103L, SELLER_B, 7_777L, 0L);
        // 대금·수수료 3건씩 + 배송비 1건 = 7건
        awaitCandidates(NORMAL_ORDER_ID, 7);
        backdateCandidates();

        // when
        JobExecution execution = payoutJobRunner.run();

        // then: 실패 없이 정산 (A = 9,700 + 4,850 + 3,000, B = 7,777 − 233, 시스템 = 300 + 150 + 233)
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long snapshotId = snapshotIdOf(execution);
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(SELLER_A, 17_550L),
                        tuple(SELLER_B, 7_544L),
                        tuple(null, 683L));
    }

    @Test
    void 결제_금액보다_큰_구매확정이_들어온_주문만_잠기고_다음_배치에서도_빠진다() throws Exception {
        // given: 정상 주문(판매자 C, 9,000원 결제·확정)과, 15,000원 결제인데 20,000원으로 구매확정된 주문(판매자 A·B)
        givenPayment(NORMAL_ORDER_ID, 9_000L);
        givenConfirmed(NORMAL_ORDER_ID, 3_000_201L, SELLER_C, 9_000L, 0L);
        givenPayment(TAMPERED_ORDER_ID, 15_000L);
        givenConfirmed(TAMPERED_ORDER_ID, 3_000_202L, SELLER_A, 12_000L, 0L);
        givenConfirmed(TAMPERED_ORDER_ID, 3_000_203L, SELLER_B, 8_000L, 0L);
        awaitCandidates(NORMAL_ORDER_ID, 2);
        awaitCandidates(TAMPERED_ORDER_ID, 4);
        backdateCandidates();

        // when: 1차 배치 → 2차 배치
        JobExecution first = payoutJobRunner.run();
        JobExecution second = payoutJobRunner.run();

        // then: 1차는 틀린 주문의 후보 4건(두 판매자 모두)만 금액 불일치로 빠지고, 정상 주문은 정산됨
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long firstSnapshotId = snapshotIdOf(first);
        assertThat(failureRepository.findBySnapshotId(firstSnapshotId))
                .extracting(PayoutFailure::getOrderId, PayoutFailure::getReason)
                .containsOnly(tuple(TAMPERED_ORDER_ID, PayoutFailureReason.AMOUNT_MISMATCH))
                .hasSize(4);
        assertThat(payoutRepository.findBySnapshotId(firstSnapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER_C, 8_730L), tuple(null, 270L));

        // then: 2차는 잠긴 주문을 스냅샷에 넣지 않아 대상이 없음 (정산·실패 기록도 새로 생기지 않음)
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long secondSnapshotId = snapshotIdOf(second);
        assertThat(snapshotItemRepository.findBySnapshotId(secondSnapshotId)).isEmpty();
        assertThat(failureRepository.findBySnapshotId(secondSnapshotId)).isEmpty();
    }

    // ---------- 도우미 ----------

    // 지갑이 주문 결제 금액을 차감했다는 이벤트 → 정산 리스너가 차감 기록을 남김
    private void givenPayment(long orderId, long amount) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                new WalletPaymentCompletedEvent(orderId, BUYER_ID, amount, LocalDateTime.now())));
        await().atMost(TIMEOUT).until(() -> deductionRepository.existsByOrderId(orderId));
    }

    // 주문 모듈이 보내는 구매확정 이벤트 → 정산 리스너가 대금·수수료(·배송비) 후보를 만듦
    // 한 건씩 처리가 끝날 때까지 기다린 뒤 다음 이벤트를 보냄:
    //   같은 판매자 묶음의 이벤트가 리스너에서 동시에 처리되면 배송비 UK에 걸려 한쪽이 롤백되고 재처리를 기다리게 됨
    //   (PayoutCandidateService 주석의 동시 처리 경우. 이 테스트가 보려는 금액 검사와 상관없는 경합이라 피함)
    private void givenConfirmed(long orderId, long orderItemId, long sellerId, long itemPrice, long sellerShippingPrice) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                new OrderProductConfirmedEvent(orderId, orderItemId, sellerId, itemPrice, sellerShippingPrice)));
        await().atMost(TIMEOUT).until(() -> candidateRepository.existsByOrderItemIdAndEventTypeInAndAdjustmentId(
                orderItemId, PayoutEventType.PURCHASE_CONFIRMED_TYPES, PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID));
    }

    private void awaitCandidates(long orderId, int count) {
        await().atMost(TIMEOUT).until(() -> candidateRepository.findAllByOrderId(orderId).size() == count);
    }

    // 정산은 생성 15일 뒤가 대상이라 이 클래스 후보의 생성 시각을 16일 전으로 옮김
    // 16일인 이유: 2차 배치의 조회 하한은 "마지막 정산일(오늘) − 18일"이라, 더 과거(예: 20일 전)로 옮기면
    //   잠금과 상관없이 기간 밖이라 빠져서 "잠금 때문에 빠졌다"를 확인할 수 없음
    // (JPA 저장은 Auditing이 현재 시각으로 채우므로 SQL로 직접 바꿈, 시간대는 Asia/Seoul로 고정되어 그대로 비교됨)
    private void backdateCandidates() {
        LocalDateTime createdAt = LocalDateTime.now().minusDays(16);
        jdbcTemplate.update(
                "UPDATE payout_candidate_items SET created_at = ?, updated_at = ? WHERE order_id > ? AND order_id <= ?",
                createdAt, createdAt, ORDER_ID_START, ORDER_ID_END);
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

    // 정산·draft·스냅샷·실패 기록은 전부, 후보·차감 기록은 이 클래스 번호대만 지움 (FK 때문에 자식 테이블 먼저)
    // (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    private void cleanUp() {
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
