package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.exception.PayoutErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;

// 대기 건너뛰기 설정이 꺼진 경우만 여기서 확인함 (#389)
// 통합 테스트는 공용 테스트 설정에서 켠 상태로 돌아서(컨텍스트를 하나로 공유하기 위함, testing-strategy.md), 꺼짐은 단위 테스트가 맡음
@ExtendWith(MockitoExtension.class)
class PayoutJobRunnerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneId.of("Asia/Seoul"));

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
        // Job을 시작하지 않는 것 자체가 요구사항이라 호출 여부를 확인함
        then(jobOperator).shouldHaveNoInteractions();
    }
}
