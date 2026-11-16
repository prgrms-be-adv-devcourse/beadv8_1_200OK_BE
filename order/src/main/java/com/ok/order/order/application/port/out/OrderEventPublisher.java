package com.ok.order.order.application.port.out;

import com.ok.order.order.application.dto.PaidOrderMessage;

public interface OrderEventPublisher {
    /**
     * 지갑에 결제를 요청한다. 주문을 잠그고 결제 요청 시각을 기록하는 트랜잭션 안에서 부른다.
     *
     * @param orderSummary 지갑 이용 내역에 보일 주문 요약(예: "사과 외 3건"). 항상 값이 있다
     */
    void publishOrderCreatedEvent(Long orderId, Long buyerId, Long totalAmount, String orderSummary);
    void publishOrderProductConfirmedEvent(Long orderId, Long orderItemId, Long sellerId, Long totalItemPrice, Long sellerShippingPrice);
    void publishOrderPaidEvent(PaidOrderMessage message);
}
