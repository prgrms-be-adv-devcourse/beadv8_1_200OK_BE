package com.ok.payout.adapter.in.batch;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.exception.PayoutErrorCode;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 단위 테스트: 대기 건너뛰기 스위치의 꺼짐·켜짐 분기만 확인 (#485)
// 켜진 상태의 실제 정산 흐름은 PayoutSkipWaitRunIntegrationTest가 module-test.yml로 켜서 확인함
@ExtendWith(MockitoExtension.class)
class PayoutJobRunnerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"), ZONE);

    @Mock
    private JobOperator jobOperator;

    @Mock
    private Job payoutJob;

    // 잠금 판단은 진짜 DefaultLockingTaskExecutor가 하고, 잠금 저장소만 가짜로 둠
    private final FakeLockProvider lockProvider = new FakeLockProvider();
    private final LockingTaskExecutor lockingExecutor = new DefaultLockingTaskExecutor(lockProvider);

    @Test
    void 정기_실행이_잠금을_못_잡으면_실행_중_예외를_던지고_Job을_시작하지_않는다() {
        // given
        lockProvider.held = true;
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, false);

        // when & then
        assertThatThrownBy(sut::runScheduled)
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_JOB_ALREADY_RUNNING));
        verifyNoInteractions(jobOperator);
    }

    @Test
    void 관리자_실행이_잠금을_못_잡으면_실행_중_예외를_던지고_Job을_시작하지_않는다() {
        // given
        lockProvider.held = true;
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, false);

        // when & then
        assertThatThrownBy(sut::runManual)
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_JOB_ALREADY_RUNNING));
        verifyNoInteractions(jobOperator);
    }

    @Test
    void 대기_건너뛰기_실행이_잠금을_못_잡으면_실행_중_예외를_던지고_Job을_시작하지_않는다() {
        // given
        lockProvider.held = true;
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, true);

        // when & then
        assertThatThrownBy(sut::runSkippingWait)
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_JOB_ALREADY_RUNNING));
        verifyNoInteractions(jobOperator);
    }

    @Test
    void 잠금을_잡으면_Job_결과를_그대로_돌려주고_끝나면_잠금을_푼다() throws Exception {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, false);
        JobExecution expected = new JobExecution(1L, null, new JobParameters());
        given(jobOperator.start(eq(payoutJob), any(JobParameters.class))).willReturn(expected);

        // when
        JobExecution actual = sut.runManual();

        // then
        assertThat(actual).isSameAs(expected);
        assertThat(lockProvider.unlockCount).isEqualTo(1);
    }

    @Test
    void Job이_예외를_던져도_그대로_위로_보내고_잠금은_푼다() throws Exception {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, false);
        IllegalStateException failure = new IllegalStateException("Job 시작 실패");
        given(jobOperator.start(eq(payoutJob), any(JobParameters.class))).willThrow(failure);

        // when & then
        assertThatThrownBy(sut::runManual).isSameAs(failure);
        assertThat(lockProvider.unlockCount).isEqualTo(1);
    }

    @Test
    void 잠금은_이름_payoutJob_최소_유지_0으로_요청한다() throws Exception {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, false);
        given(jobOperator.start(eq(payoutJob), any(JobParameters.class))).willReturn(new JobExecution(1L, null, new JobParameters()));

        // when
        sut.runScheduled();

        // then
        LockConfiguration config = lockProvider.requested;
        assertSoftly(softly -> {
            softly.assertThat(config.getName()).isEqualTo("payoutJob");
            softly.assertThat(config.getLockAtLeastFor()).isEqualTo(Duration.ZERO);
            softly.assertThat(config.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    // held가 true면 이미 다른 곳이 잡은 것처럼 빈 값을 돌려줌
    private static class FakeLockProvider implements LockProvider {
        boolean held;
        int unlockCount;
        LockConfiguration requested;

        @Override
        public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
            requested = lockConfiguration;
            if (held) {
                return Optional.empty();
            }
            return Optional.of(() -> unlockCount++);
        }
    }

    @Test
    void 대기_건너뛰기_설정이_꺼져_있으면_409로_거절하고_Job을_시작하지_않는다() {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, false);

        // when & then
        assertThatThrownBy(sut::runSkippingWait)
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.SKIP_WAIT_RUN_DISABLED));
        verifyNoInteractions(jobOperator);
    }

    @Test
    void 대기_건너뛰기_설정이_켜져_있으면_실행_기준_시각을_17일_미래로_밀어_Job을_시작한다() throws Exception {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, true);
        JobExecution expected = new JobExecution(1L, null, new JobParameters());
        given(jobOperator.start(eq(payoutJob), any(JobParameters.class))).willReturn(expected);

        // when
        JobExecution actual = sut.runSkippingWait();

        // then
        assertThat(actual).isSameAs(expected);
        assertStartedWith(LocalDateTime.now(CLOCK).plusDays(PayoutPolicy.SETTLEMENT_WAITING_DAYS + 2), "SKIP_WAIT");
    }

    @Test
    void 정기_실행은_현재_시각과_실행_유형_SCHEDULED로_Job을_시작한다() throws Exception {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, false);
        given(jobOperator.start(eq(payoutJob), any(JobParameters.class))).willReturn(new JobExecution(1L, null, new JobParameters()));

        // when
        sut.runScheduled();

        // then
        assertStartedWith(LocalDateTime.now(CLOCK), "SCHEDULED");
    }

    @Test
    void 관리자_실행은_현재_시각과_실행_유형_MANUAL로_Job을_시작한다() throws Exception {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, lockingExecutor, CLOCK, false);
        given(jobOperator.start(eq(payoutJob), any(JobParameters.class))).willReturn(new JobExecution(1L, null, new JobParameters()));

        // when
        sut.runManual();

        // then
        assertStartedWith(LocalDateTime.now(CLOCK), "MANUAL");
    }

    // Spring Batch 6.0.5의 JobParameter.equals는 이름만 비교하므로 verify(start(job, 기대 파라미터))로는 값을 확인할 수 없다.
    // 그래서 실제로 넘어간 JobParameters를 잡아 값과 identifying 여부를 직접 단언한다.
    // 실행 유형은 JobInstance를 가르지 않는 기록용(identifying = false), requestedAt은 가르는 키(identifying = true)
    private void assertStartedWith(LocalDateTime requestedAt, String runType) throws Exception {
        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(payoutJob), captor.capture());
        JobParameters actual = captor.getValue();

        assertSoftly(softly -> {
            softly.assertThat(actual.getLocalDateTime("requestedAt")).isEqualTo(requestedAt);
            softly.assertThat(actual.getString("runType")).isEqualTo(runType);
            softly.assertThat(actual.getParameter("requestedAt").identifying()).isTrue();
            softly.assertThat(actual.getParameter("runType").identifying()).isFalse();
        });
    }
}
