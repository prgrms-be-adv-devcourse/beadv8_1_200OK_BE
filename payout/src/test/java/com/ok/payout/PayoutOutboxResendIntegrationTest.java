package com.ok.payout;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.in.batch.PayoutOutboxResendScheduler;
import com.ok.payout.adapter.in.batch.PayoutOutboxSendLock;
import com.ok.payout.adapter.out.persistence.PayoutOutboxJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.application.port.in.PayoutPublishResult;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutOutbox;
import com.ok.payout.domain.PayoutOutboxStatus;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.testsupport.AbstractIntegrationTest;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트: 정산 Job 발행 단계와 짧은 간격 재발송이 같은 잠금(payoutOutboxSend)을 실제 MySQL에서 함께 쓰는지 확인 (#620)
//
// [확인하는 것]
//   - 다른 곳이 잠금을 잡고 있으면 정산 Job 발행 단계는 보내지 않고 넘어가고(Job은 COMPLETED), 재발송도 보내지 않음
//     → 같은 줄을 두 곳이 동시에 보내지 않음
//   - 발행 단계에서 못 보낸 줄을 잠금이 풀린 뒤 재발송이 보냄 → 다음 정산 Job을 기다리지 않음
//
// [잠금을 잡는 방법]
// 다른 스레드에서 같은 이름으로 executeWithLock을 부르고, 테스트가 끝낼 때까지 그 안에서 기다리게 함
// 같은 스레드에서 잡으면 ShedLock이 "이미 내가 잡은 잠금"으로 보고 안쪽 실행을 그대로 허락해 겹침을 만들 수 없음
// 잠금 행을 직접 고치지 않는 이유: 같은 컨텍스트의 LockProvider가 행이 있다고 기억하는 상태와 어긋날 수 있음 (PayoutJobLockIntegrationTest 주석)
class PayoutOutboxResendIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문 번호대 (다른 정산 통합 테스트가 쓰지 않는 범위). 주문항목 ID에도 같은 번호를 씀
    private static final long ORDER_ID_START = 21_000_000L;
    private static final long ORDER_ID_END = 22_000_000L;
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(ORDER_ID_START);

    // PayoutOutboxSendLock의 잠금 이름 (그 상수는 패키지 안에서만 보임)
    private static final String OUTBOX_SEND_LOCK_NAME = "payoutOutboxSend";
    // 정상이면 1초 안에 끝나고, 넘으면 멈춘 것이라 실패시킴
    private static final long WAIT_SECONDS = 30;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private PayoutOutboxSendLock payoutOutboxSendLock;

    @Autowired
    private PayoutOutboxResendScheduler payoutOutboxResendScheduler;

    @Autowired
    private PayoutOutboxJpaRepository outboxRepository;

    @Autowired
    private PayoutWalletDeductionJpaRepository payoutWalletDeductionRepository;

    @Autowired
    private LockingTaskExecutor payoutJobLockingTaskExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        cleanUp();
    }

    @Test
    void 잠금이_잡혀_있으면_발행_단계는_보내지_않고_풀린_뒤_재발송이_보낸다() throws Exception {
        // given: 다른 곳(예: 다른 서버의 재발송)이 outbox 발송 잠금을 잡고 있음
        insertCandidate(nextOrderId(), 10L, 9_700L);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdOutboxSendLock(release);

        // when: 그동안 정산 Job이 돎
        JobExecution execution = payoutJobRunner.runScheduled();

        // then: Job은 정상 종료, 발행 단계는 0건이고 줄은 PENDING으로 남음
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution publishStep = publishStepOf(execution);
        assertThat(publishStep).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(publishStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            softly.assertThat(publishStep.getWriteCount()).isZero();
        });
        PayoutOutbox pending = singleOutbox();
        assertThat(pending.getStatus()).isEqualTo(PayoutOutboxStatus.PENDING);

        // when: 잠금이 풀린 뒤 짧은 간격 재발송이 돎 (실제로는 5분마다, 여기서는 직접 부름)
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        payoutOutboxResendScheduler.resendPending();

        // then: 다음 정산 Job을 기다리지 않고 보냄 (보냄 기록은 Kafka 저장 확인을 받은 뒤에만 남음)
        PayoutOutbox sut = singleOutbox();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutOutboxStatus.SENT);
            softly.assertThat(sut.getSentAt()).isNotNull();
        });
    }

    @Test
    void 잠금이_잡혀_있으면_재발송도_보내지_않는다() throws Exception {
        // given: PENDING 줄 1개를 두고(정산 Job은 잠금 없이 보냈으므로 PENDING으로 되돌림), 다른 곳이 잠금을 잡고 있음
        insertCandidate(nextOrderId(), 10L, 9_700L);
        assertThat(payoutJobRunner.runScheduled().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        PayoutOutbox outbox = singleOutbox();
        outbox.markPendingAgain();
        outboxRepository.save(outbox);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdOutboxSendLock(release);

        try {
            // when
            Optional<PayoutPublishResult> sut = payoutOutboxSendLock.publishPending();

            // then: 보내지 않았고 줄은 PENDING 그대로
            assertThat(sut).isEmpty();
            assertThat(singleOutbox().getStatus()).isEqualTo(PayoutOutboxStatus.PENDING);
        } finally {
            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    // ---------- 도우미 ----------

    // 다른 스레드에서 outbox 발송 잠금을 잡고 release가 열릴 때까지 쥐고 있음. 잠금을 잡은 뒤에 돌아옴
    private Future<?> holdOutboxSendLock(CountDownLatch release) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        LockConfiguration lockConfig = new LockConfiguration(
                Instant.now(), OUTBOX_SEND_LOCK_NAME, Duration.ofMinutes(10), Duration.ZERO);
        Future<?> holder = executor.submit(() -> {
            payoutJobLockingTaskExecutor.executeWithLock((Runnable) () -> {
                acquired.countDown();
                try {
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, lockConfig);
            return null;
        });
        assertThat(acquired.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return holder;
    }

    private StepExecution publishStepOf(JobExecution execution) {
        for (StepExecution step : execution.getStepExecutions()) {
            if ("payoutPublishStep".equals(step.getStepName())) {
                return step;
            }
        }
        return null;
    }

    private PayoutOutbox singleOutbox() {
        List<PayoutOutbox> found = outboxRepository.findAll();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    // 정산 후보를 대기 기간이 지난 created_at으로 직접 저장 (+ 그 주문의 지갑 차감 기록). PayoutDepositCheckIntegrationTest와 같은 SQL
    // 저장 시 Auditing이 created_at을 현재 시각으로 덮어써서 JPA로는 과거 시각을 넣을 수 없음
    private void insertCandidate(long orderId, Long sellerId, Long amount) {
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                PayoutEventType.SALE_AMOUNT.name(), orderId, orderId, sellerId, amount, createdAt, createdAt);
        payoutWalletDeductionRepository.save(PayoutWalletDeduction.create(orderId, 1L, 1_000_000L, LocalDateTime.now()));
    }

    private long nextOrderId() {
        return ORDER_ID_SEQ.incrementAndGet();
    }

    // PayoutDepositCheckIntegrationTest와 같은 순서로 지움 (FK 때문에 자식 테이블 먼저)
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
