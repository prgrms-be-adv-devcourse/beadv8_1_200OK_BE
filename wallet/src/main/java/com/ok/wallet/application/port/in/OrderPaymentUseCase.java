package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.OrderPaymentCommand;

/**
 * 주문 결제 유스케이스.
 */
public interface OrderPaymentUseCase {

    /**
     * 주문 결제 금액을 차감하고 결과 이벤트를 발행한다. Spring 이벤트로 받은 주문을 처리한다.
     * 같은 주문이 다시 오면 결과를 다시 보내지 않는다. 결과가 차감과 같은 트랜잭션에서 저장돼 반드시 전달되기 때문이다.
     */
    void pay(OrderPaymentCommand command);

    /**
     * 주문 결제 금액을 차감하고, 커밋한 뒤 결과를 Kafka로 보낸다. Kafka로 받은 주문을 처리한다.
     * 같은 주문이 다시 오면 돈은 다시 빼지 않고 처음 결과를 다시 보낸다.
     *
     * @throws IllegalArgumentException 주문·구매자 ID가 없으면. 결과를 알릴 주문을 몰라 다시 처리해도 같다
     * @throws IllegalStateException    결과를 보내지 못했으면. 결제는 이미 기록돼 있어 다시 처리하면 처음 결과를 다시 보낸다
     */
    void payAndSendResult(OrderPaymentCommand command);
}
