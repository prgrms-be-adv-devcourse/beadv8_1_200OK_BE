package com.ok.payout.adapter.in.event;

import com.ok.payout.adapter.in.event.dto.WalletPaymentCompletedMessage;
import com.ok.payout.application.port.in.PayoutWalletDeductionUseCase;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

// 단위 테스트: Spring·Kafka 없이, 입력 포트는 Mockito 가짜 객체로 대체
@ExtendWith(MockitoExtension.class)
class PayoutWalletKafkaListenerTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 14, 0);

    @Mock
    private PayoutWalletDeductionUseCase payoutWalletDeductionUseCase;

    private PayoutWalletKafkaListener sut;

    @BeforeEach
    void setUp() {
        sut = new PayoutWalletKafkaListener(payoutWalletDeductionUseCase);
    }

    @ParameterizedTest
    @MethodSource("값이_틀린_지갑_차감_메시지")
    void 값이_틀린_지갑_차감이면_입력_포트를_부르지_않는다(WalletPaymentCompletedMessage message) {
        // when
        sut.onKafka(message);

        // then: 예외 없이 끝나고 차감 기록 저장은 요청하지 않는다 (요구사항 자체가 "부르지 않는다")
        then(payoutWalletDeductionUseCase).shouldHaveNoInteractions();
    }

    static Stream<WalletPaymentCompletedMessage> 값이_틀린_지갑_차감_메시지() {
        return Stream.of(
                new WalletPaymentCompletedMessage(null, 1L, 13_000L, PAID_AT),
                new WalletPaymentCompletedMessage(100L, null, 13_000L, PAID_AT),
                new WalletPaymentCompletedMessage(100L, 1L, null, PAID_AT),
                new WalletPaymentCompletedMessage(100L, 1L, 13_000L, null),
                new WalletPaymentCompletedMessage(100L, 1L, -1L, PAID_AT)
        );
    }

    @Test
    void 값이_맞는_지갑_차감이면_입력_포트를_부른다() {
        // given: 필드마다 다른 값을 넣어 순서가 바뀌면 잡히게 한다
        WalletPaymentCompletedMessage message = new WalletPaymentCompletedMessage(100L, 1L, 13_000L, PAID_AT);

        // when
        sut.onKafka(message);

        // then
        ArgumentCaptor<WalletPaymentCompletedCommand> captor = ArgumentCaptor.forClass(WalletPaymentCompletedCommand.class);
        then(payoutWalletDeductionUseCase).should().saveDeduction(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new WalletPaymentCompletedCommand(100L, 1L, 13_000L, PAID_AT));
    }
}
