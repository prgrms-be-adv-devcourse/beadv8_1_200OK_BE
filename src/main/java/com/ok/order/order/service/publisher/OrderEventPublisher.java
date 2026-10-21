package com.ok.order.order.service.publisher;

import com.ok.order.order.service.dto.PaidOrderMessage;

public interface OrderEventPublisher {
    void publishOrderCreatedEvent(Long orderId, Long buyerId, Long totalAmount);
    void publishOrderProductConfirmedEvent(Long orderId, Long orderItemId, Long sellerId, Long totalItemPrice, Long sellerShippingPrice);
    void publishOrderPaidEvent(PaidOrderMessage message);
}
