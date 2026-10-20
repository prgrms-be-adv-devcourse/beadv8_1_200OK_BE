package com.ok.order.order.service.publisher;

public interface OrderEventPublisher {
    void publishOrderCreatedEvent(Long orderId, Long buyerId, Long totalAmount);
    void publishOrderProductConfirmedEvent(Long orderId, Long orderItemId, Long sellerId, Long totalItemPrice, Long sellerShippingPrice);
}
