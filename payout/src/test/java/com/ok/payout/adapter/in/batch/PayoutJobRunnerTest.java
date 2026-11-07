package com.ok.payout.adapter.in.batch;

import com.ok.common.exception.RestApiException;
import com.ok.payout.exception.PayoutErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void 대기_건너뛰기_설정이_꺼져_있으면_409로_거절하고_Job을_시작하지_않는다() {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, CLOCK, false);

        // when & then
        assertThatThrownBy(sut::runSkippingWait)
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.SKIP_WAIT_RUN_DISABLED));
        verifyNoInteractions(jobOperator);
    }

    @Test
    void 대기_건너뛰기_설정이_켜져_있으면_실행_기준_시각을_17일_미래로_밀어_Job을_시작한다() throws Exception {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, CLOCK, true);
        JobExecution expected = new JobExecution(1L, null, new JobParameters());
        given(jobOperator.start(eq(payoutJob), any(JobParameters.class))).willReturn(expected);

        // when
        JobExecution actual = sut.runSkippingWait();

        // then
        assertThat(actual).isSameAs(expected);
        JobParameters expectedParams = new JobParametersBuilder()
                .addLocalDateTime("requestedAt", LocalDateTime.now(CLOCK).plusDays(17))
                .addString("runType", "SKIP_WAIT", false)
                .toJobParameters();
        verify(jobOperator).start(payoutJob, expectedParams);
    }

    @Test
    void 정기_실행은_현재_시각과_실행_유형_SCHEDULED로_Job을_시작한다() throws Exception {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, CLOCK, false);
        given(jobOperator.start(eq(payoutJob), any(JobParameters.class))).willReturn(new JobExecution(1L, null, new JobParameters()));

        // when
        sut.runScheduled();

        // then
        verify(jobOperator).start(payoutJob, paramsOf("SCHEDULED"));
    }

    @Test
    void 관리자_실행은_현재_시각과_실행_유형_MANUAL로_Job을_시작한다() throws Exception {
        // given
        PayoutJobRunner sut = new PayoutJobRunner(jobOperator, payoutJob, CLOCK, false);
        given(jobOperator.start(eq(payoutJob), any(JobParameters.class))).willReturn(new JobExecution(1L, null, new JobParameters()));

        // when
        sut.runManual();

        // then
        verify(jobOperator).start(payoutJob, paramsOf("MANUAL"));
    }

    // 실행 유형은 JobInstance를 가르지 않는 기록용(identifying = false) 파라미터
    private JobParameters paramsOf(String runType) {
        return new JobParametersBuilder()
                .addLocalDateTime("requestedAt", LocalDateTime.now(CLOCK))
                .addString("runType", runType, false)
                .toJobParameters();
    }
}
