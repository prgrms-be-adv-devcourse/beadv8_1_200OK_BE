package com.ok.wallet.application.dto;

import com.ok.wallet.domain.WalletOrderPaymentStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPaymentResultTest {

    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2026, 11, 15, 10, 30);

    @Test
    void 실패로_기록된_결제의_사유는_잔액_부족이다() {
        // given
        OrderPaymentResult sut = OrderPaymentResult.processed(
                10L, 1L, 3_000, WalletOrderPaymentStatus.FAILED, PROCESSED_AT);

        // when
        OrderPaymentFailureReason reason = sut.failureReason();

        // then: Spring 길과 Kafka 길, 처음과 다시 보낼 때 모두 이 사유가 나간다
        assertThat(reason).isEqualTo(OrderPaymentFailureReason.INSUFFICIENT_BALANCE);
    }

    @Test
    void 결제된_결과에서_실패_사유를_찾으면_예외가_난다() {
        // given
        OrderPaymentResult sut = OrderPaymentResult.processed(
                10L, 1L, 3_000, WalletOrderPaymentStatus.PAID, PROCESSED_AT);

        // when & then: 결제된 주문에 실패를 보내는 실수를 바로 드러낸다
        assertThatThrownBy(sut::failureReason)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("결제된 주문에는 실패 사유가 없습니다. orderId=10");
    }
}
