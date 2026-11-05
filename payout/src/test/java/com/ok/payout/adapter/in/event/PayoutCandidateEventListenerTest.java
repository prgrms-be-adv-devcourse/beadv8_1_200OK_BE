package com.ok.payout.adapter.in.event;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.payout.application.port.in.PayoutCandidateUseCase;
import com.ok.payout.application.port.in.PurchaseConfirmedCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

// 단위 테스트: Spring 없이, 입력 포트는 Mockito 가짜 객체로 대체
@ExtendWith(MockitoExtension.class)
class PayoutCandidateEventListenerTest {

    @Mock
    private PayoutCandidateUseCase payoutCandidateUseCase;

    private PayoutCandidateEventListener sut;

    @BeforeEach
    void setUp() {
        sut = new PayoutCandidateEventListener(payoutCandidateUseCase);
    }

    @Test
    void 값이_틀린_구매확정이면_입력_포트를_부르지_않는다() {
        // given: 상품 총액이 음수
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(100L, 1L, 10L, -1L, 3_000L);

        // when
        sut.on(event);

        // then: 예외 없이 끝나고 정산후보 생성은 요청하지 않는다 (요구사항 자체가 "부르지 않는다")
        then(payoutCandidateUseCase).shouldHaveNoInteractions();
    }

    @Test
    void 값이_맞는_구매확정이면_입력_포트를_부른다() {
        // given: 필드마다 다른 값을 넣어 순서가 바뀌면 잡히게 한다
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(100L, 1L, 10L, 10_000L, 3_000L);

        // when
        sut.on(event);

        // then
        ArgumentCaptor<PurchaseConfirmedCommand> captor = ArgumentCaptor.forClass(PurchaseConfirmedCommand.class);
        then(payoutCandidateUseCase).should().createFromPurchaseConfirmed(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PurchaseConfirmedCommand(100L, 1L, 10L, 10_000L, 3_000L));
    }

    @Test
    void 입력_포트에서_예외가_나면_그대로_던진다() {
        // given: 값은 맞지만 입력 포트가 DB 오류로 실패
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(100L, 1L, 10L, 10_000L, 3_000L);
        RuntimeException failure = new RuntimeException("DB 오류");
        willThrow(failure).given(payoutCandidateUseCase).createFromPurchaseConfirmed(any());

        // when & then: 삼키지 않고 그대로 던져 재처리 대상으로 남긴다
        assertThatThrownBy(() -> sut.on(event))
                .isSameAs(failure);
    }
}
