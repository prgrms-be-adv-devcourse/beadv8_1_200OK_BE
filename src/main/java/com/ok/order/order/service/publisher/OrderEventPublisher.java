package com.ok.order.order.service.publisher;

public interface OrderEventPublisher {
    void publishOrderCreatedEvent(Long orderId, Long buyerId, long totalAmount);
}
