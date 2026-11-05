package com.ok.payout.adapter.in.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.payout.application.port.in.PayoutWalletDeductionUseCase;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

// 단위 테스트: Spring 없이, 입력 포트는 Mockito 가짜 객체로 대체
@ExtendWith(MockitoExtension.class)
class PayoutWalletEventListenerTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 14, 0);

    @Mock
    private PayoutWalletDeductionUseCase payoutWalletDeductionUseCase;

    private PayoutWalletEventListener sut;

    @BeforeEach
    void setUp() {
        sut = new PayoutWalletEventListener(payoutWalletDeductionUseCase);
    }

    @Test
    void 값이_틀린_지갑_차감이면_입력_포트를_부르지_않는다() {
        // given: 차감 금액이 음수 (null은 공용 이벤트 생성자가 먼저 막아 이 입구까지 오지 않는다)
        WalletPaymentCompletedEvent event = new WalletPaymentCompletedEvent(100L, 1L, -1L, PAID_AT);

        // when
        sut.on(event);

        // then: 예외 없이 끝나고 차감 기록 저장은 요청하지 않는다 (요구사항 자체가 "부르지 않는다")
        then(payoutWalletDeductionUseCase).shouldHaveNoInteractions();
    }

    @Test
    void 값이_맞는_지갑_차감이면_입력_포트를_부른다() {
        // given
        WalletPaymentCompletedEvent event = new WalletPaymentCompletedEvent(100L, 1L, 13_000L, PAID_AT);

        // when
        sut.on(event);

        // then
        ArgumentCaptor<WalletPaymentCompletedCommand> captor = ArgumentCaptor.forClass(WalletPaymentCompletedCommand.class);
        then(payoutWalletDeductionUseCase).should().saveDeduction(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new WalletPaymentCompletedCommand(100L, 1L, 13_000L, PAID_AT));
    }
}
