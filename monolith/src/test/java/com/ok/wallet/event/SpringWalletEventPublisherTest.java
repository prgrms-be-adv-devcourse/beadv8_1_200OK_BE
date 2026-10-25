package com.ok.wallet.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.common.event.WalletPaymentFailedEvent;
import com.ok.wallet.service.dto.OrderPaymentFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SpringWalletEventPublisherTest {
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 12, 0);

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    SpringWalletEventPublisher sut;

    @Test
    void 결제_완료를_알리면_주문_구매자_금액_시각으로_완료_이벤트를_만들어_발행한다() {
        // when
        sut.publishOrderPaymentCompleted(1L, 10L, 10_000, PAID_AT);

        // then
        then(applicationEventPublisher).should()
                .publishEvent(new WalletPaymentCompletedEvent(1L, 10L, 10_000L, PAID_AT));
    }

    // 사유 문자열은 주문과의 계약이라, 상수 값이 바뀌면 이 테스트가 깨지도록 기대값을 직접 적는다
    @ParameterizedTest
    @CsvSource({
            "INSUFFICIENT_BALANCE, INSUFFICIENT_BALANCE",
            "INVALID_PAYMENT_REQUEST, INVALID_PAYMENT_REQUEST"
    })
    void 결제_실패를_알리면_지갑_사유를_이벤트_사유로_바꿔_실패_이벤트를_발행한다(
            OrderPaymentFailureReason reason, String expectedEventReason) {
        // when
        sut.publishOrderPaymentFailed(1L, 10L, reason);

        // then
        then(applicationEventPublisher).should()
                .publishEvent(new WalletPaymentFailedEvent(1L, 10L, expectedEventReason));
    }

    @Test
    void 주문_id가_null이면_예외가_발생하고_이벤트를_발행하지_않는다() {
        // when & then
        assertThatThrownBy(() -> sut.publishOrderPaymentCompleted(null, 10L, 10_000, PAID_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("orderId는 필수입니다.");
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }
}
