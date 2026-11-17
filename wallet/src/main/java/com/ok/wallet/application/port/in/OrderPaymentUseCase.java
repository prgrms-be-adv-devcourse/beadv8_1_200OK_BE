package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.OrderPaymentCommand;
import com.ok.wallet.application.dto.OrderPaymentExpireCommand;

/**
 * 주문 결제 유스케이스.
 */
public interface OrderPaymentUseCase {

    /**
     * 주문 결제 금액을 차감하고, 커밋한 뒤 결과를 Kafka로 보낸다.
     * 같은 주문이 다시 오면 돈은 다시 빼지 않고 처음 결과를 다시 보낸다.
     *
     * @throws IllegalArgumentException 주문·구매자 ID가 없으면. 결과를 알릴 주문을 몰라 다시 처리해도 같다
     * @throws IllegalStateException    결과를 보내지 못했으면. 결제는 이미 기록돼 있어 다시 처리하면 처음 결과를 다시 보낸다
     */
    void payAndSendResult(OrderPaymentCommand command);

    /**
     * 주문의 결제 만료 요청을 받아 그 주문의 결과를 정하고, 커밋한 뒤 결과를 Kafka로 보낸다.
     * 처음 보는 주문이면 만료로 기록하고 결제 실패({@code PAYMENT_EXPIRED})를 보낸다. 이미 처리한 주문이면 처음 결과를 다시 보낸다.
     *
     * @throws IllegalArgumentException 주문·구매자 ID나 금액이 없거나 금액이 0 이하면. 다시 처리해도 같다
     * @throws IllegalStateException    결과를 보내지 못했으면. 기록은 남아 있어 다시 처리하면 처음 결과를 다시 보낸다
     */
    void expireAndSendResult(OrderPaymentExpireCommand command);
}
