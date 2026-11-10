package com.ok.order.order.application.port.out;

import com.ok.order.order.application.dto.PaidOrderMessage;

public interface OrderEventPublisher {
    void publishOrderCreatedEvent(Long orderId, Long buyerId, Long totalAmount);
    void publishOrderProductConfirmedEvent(Long orderId, Long orderItemId, Long sellerId, Long totalItemPrice, Long sellerShippingPrice);
    void publishOrderPaidEvent(PaidOrderMessage message);
}
