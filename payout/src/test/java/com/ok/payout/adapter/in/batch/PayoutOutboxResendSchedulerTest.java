package com.ok.payout.adapter.in.batch;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

// 단위 테스트: 짧은 간격 재발송이 잠금을 못 잡아도 오류 없이 건너뛰는지 (#620)
// 실제 잠금으로 같은 줄을 동시에 보내지 않는지는 PayoutOutboxResendIntegrationTest에서 확인
@ExtendWith(MockitoExtension.class)
class PayoutOutboxResendSchedulerTest {

    @Mock
    private PayoutOutboxSendLock payoutOutboxSendLock;

    @InjectMocks
    private PayoutOutboxResendScheduler sut;

    // 5분마다 도는 일이라, 정산 Job 발행 단계와 겹친 것은 정상 상황이고 예외로 스케줄러 오류 로그를 남기면 안 됨
    @Test
    void 다른_곳이_보내는_중이면_오류_없이_건너뛴다() {
        // given
        given(payoutOutboxSendLock.publishPending()).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> sut.resendPending()).doesNotThrowAnyException();
    }
}
