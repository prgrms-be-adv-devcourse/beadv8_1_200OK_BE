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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

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
    void 값이_틀린_지갑_차감이면_예외를_던지고_입력_포트를_부르지_않는다(WalletPaymentCompletedMessage message) {
        // when & then: 규칙대로 표준 예외를 던진다. 메시지에 검색용 태그가 들어간다 (이유별 문구는 검사 클래스 테스트가 확인)
        assertThatThrownBy(() -> sut.onKafka(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[PAYOUT_WALLET_DEDUCTION_SKIPPED]");
        // 차감 기록 저장은 요청하지 않는다 (요구사항 자체가 "부르지 않는다")
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

    @Test
    void 입력_포트에서_예외가_나면_그대로_던진다() {
        // given: 값은 맞지만 입력 포트가 DB 오류로 실패
        WalletPaymentCompletedMessage message = new WalletPaymentCompletedMessage(100L, 1L, 13_000L, PAID_AT);
        RuntimeException failure = new RuntimeException("DB 오류");
        willThrow(failure).given(payoutWalletDeductionUseCase).saveDeduction(any());

        // when & then: 삼키지 않고 그대로 던져 재처리 대상으로 남긴다
        assertThatThrownBy(() -> sut.onKafka(message))
                .isSameAs(failure);
    }
}
