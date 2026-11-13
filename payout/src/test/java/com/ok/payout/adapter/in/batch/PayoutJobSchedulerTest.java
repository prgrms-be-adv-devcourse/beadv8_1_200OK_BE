package com.ok.payout.adapter.in.batch;

import com.ok.common.exception.RestApiException;
import com.ok.payout.exception.PayoutErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

// 단위 테스트: 이미 실행 중일 때만 정기 실행을 조용히 건너뛰는지 확인 (#223)
@ExtendWith(MockitoExtension.class)
class PayoutJobSchedulerTest {

    @Mock
    private PayoutJobRunner payoutJobRunner;

    @InjectMocks
    private PayoutJobScheduler sut;

    @Test
    void 이미_실행_중이면_예외_없이_건너뛴다() throws Exception {
        // given
        given(payoutJobRunner.runScheduled())
                .willThrow(new RestApiException(PayoutErrorCode.PAYOUT_JOB_ALREADY_RUNNING));

        // when & then
        assertThatCode(sut::runDaily).doesNotThrowAnyException();
    }

    @Test
    void 실행_중이_아닌_다른_RestApiException은_그대로_던진다() throws Exception {
        // given
        RestApiException other = new RestApiException(PayoutErrorCode.SKIP_WAIT_RUN_DISABLED);
        given(payoutJobRunner.runScheduled()).willThrow(other);

        // when & then
        assertThatThrownBy(sut::runDaily).isSameAs(other);
    }

    @Test
    void 그_밖의_예외도_그대로_던진다() throws Exception {
        // given
        IllegalStateException failure = new IllegalStateException("Job 실패");
        given(payoutJobRunner.runScheduled()).willThrow(failure);

        // when & then
        assertThatThrownBy(sut::runDaily).isSameAs(failure);
    }

    @Test
    void 정상_실행이면_예외_없이_끝난다() throws Exception {
        // given
        given(payoutJobRunner.runScheduled()).willReturn(new JobExecution(1L, null, new JobParameters()));

        // when & then
        assertThatCode(sut::runDaily).doesNotThrowAnyException();
    }
}
