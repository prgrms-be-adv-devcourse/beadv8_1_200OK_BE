package com.ok.order.order.application.port.in;

import com.ok.order.order.application.dto.CreateOrderCommand;

/**
 * 주문을 만든다.
 */
public interface CreateOrderUseCase {

    /**
     * 주문을 결제 대기로 저장하고 재고를 잡은 뒤 주문 생성 이벤트를 발행한다.
     * 같은 멱등키로 다시 요청하면 새로 만들지 않고 처음 만든 주문 ID를 돌려준다.
     *
     * @return 주문 ID
     * @throws com.ok.common.exception.RestApiException 금액이 바뀌었으면 {@code ORDER_AMOUNT_CHANGED}, 잔액이 모자라면 {@code INSUFFICIENT_BALANCE}, 재고가 모자라면 {@code OUT_OF_STOCK} 등
     */
    Long createOrder(CreateOrderCommand command);
}
