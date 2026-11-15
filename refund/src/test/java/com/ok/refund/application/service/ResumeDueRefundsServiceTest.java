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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

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
