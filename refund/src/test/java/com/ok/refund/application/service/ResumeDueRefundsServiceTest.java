package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.RefundSagaUseCase;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// 알람이 환불마다 예외를 잡고 넘어가는지가 요구사항이다(#575 결정 16). 빠뜨리면 환불 하나 때문에 그 회차 전체가 멈춘다
@ExtendWith(MockitoExtension.class)
class ResumeDueRefundsServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 15, 10, 0);

    @Mock
    RefundStepPersistencePort refundStepPersistencePort;
    @Mock
    RefundSagaUseCase refundSagaUseCase;

    ResumeDueRefundsService sut;

    // 호출 사이에 시간이 흐르게 하는 테스트용 시계. 고정 시계로는 한도를 넘는 경우를 만들 수 없다
    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return CLOCK.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("테스트에서 쓰지 않는다");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        sut = new ResumeDueRefundsService(refundStepPersistencePort, refundSagaUseCase, CLOCK);
    }

    @Test
    void 환불_하나가_실패해도_나머지_환불을_이어_간다() {
        // given: 1번은 다른 모듈 실패, 2번은 코드 버그 같은 예외, 3번은 정상
        given(refundStepPersistencePort.findRefundIdsDueForResume(NOW, ResumeDueRefundsService.BATCH_SIZE))
                .willReturn(List.of(1L, 2L, 3L));
        willThrow(new RestApiException(RefundErrorCode.PRODUCT_API_FAILED)).given(refundSagaUseCase).resume(1L);
        willThrow(new IllegalStateException("버그")).given(refundSagaUseCase).resume(2L);

        // when
        int count = sut.resumeDue();

        // then
        assertThat(count).isEqualTo(3);
        then(refundSagaUseCase).should().resume(3L);
    }

    // 한 회차가 길어지면 스케줄 스레드를 함께 쓰는 다른 모듈 작업이 멈춘다. 시간 한도를 넘으면 남은 환불은 다음 회차로 넘긴다(PR #628 리뷰)
    @Test
    void 시간_한도를_넘으면_남은_환불은_다음_회차로_넘긴다() {
        // given: 1번 환불을 이어 가는 데 한도(30초)를 넘게 걸린다(Kafka가 느린 경우)
        MovingClock movingClock = new MovingClock(CLOCK.instant());
        ResumeDueRefundsService service = new ResumeDueRefundsService(refundStepPersistencePort, refundSagaUseCase, movingClock);
        given(refundStepPersistencePort.findRefundIdsDueForResume(NOW, ResumeDueRefundsService.BATCH_SIZE))
                .willReturn(List.of(1L, 2L, 3L));
        willAnswer(invocation -> {
            movingClock.advance(ResumeDueRefundsService.TIME_BUDGET.plusSeconds(1));
            return null;
        }).given(refundSagaUseCase).resume(1L);

        // when
        int count = service.resumeDue();

        // then
        assertThat(count).isEqualTo(1);
        then(refundSagaUseCase).should(never()).resume(2L);
        then(refundSagaUseCase).should(never()).resume(3L);
    }

    @Test
    void 시간_한도_안이면_모든_환불을_이어_간다() {
        // given: 한 건에 29초씩 걸려도 회차 시작부터 따지므로 두 번째 환불까지만 한도 안이다
        MovingClock movingClock = new MovingClock(CLOCK.instant());
        ResumeDueRefundsService service = new ResumeDueRefundsService(refundStepPersistencePort, refundSagaUseCase, movingClock);
        given(refundStepPersistencePort.findRefundIdsDueForResume(NOW, ResumeDueRefundsService.BATCH_SIZE))
                .willReturn(List.of(1L, 2L));
        willAnswer(invocation -> {
            movingClock.advance(Duration.ofSeconds(29));
            return null;
        }).given(refundSagaUseCase).resume(1L);

        // when
        int count = service.resumeDue();

        // then
        assertThat(count).isEqualTo(2);
        then(refundSagaUseCase).should().resume(2L);
    }

    @Test
    void 다시_볼_환불이_없으면_아무것도_부르지_않는다() {
        // given
        given(refundStepPersistencePort.findRefundIdsDueForResume(NOW, ResumeDueRefundsService.BATCH_SIZE)).willReturn(List.of());

        // when
        int count = sut.resumeDue();

        // then
        assertThat(count).isZero();
        then(refundSagaUseCase).shouldHaveNoInteractions();
    }
}
