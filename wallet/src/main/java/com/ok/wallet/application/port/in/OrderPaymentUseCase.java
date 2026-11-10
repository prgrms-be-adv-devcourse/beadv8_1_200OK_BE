package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.OrderPaymentCommand;

/**
 * 주문 결제 유스케이스.
 */
public interface OrderPaymentUseCase {

    /**
     * 주문 결제 금액을 차감하고 결과 이벤트를 발행한다.
     */
    void pay(OrderPaymentCommand command);
}
