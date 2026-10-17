package com.ok.order.order.event;

import com.ok.common.event.OrderCreatedEvent;
import com.ok.order.order.service.publisher.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringOrderEventPublisher implements OrderEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishOrderCreatedEvent(Long orderId, Long buyerId, long totalAmount) {
        applicationEventPublisher.publishEvent(new OrderCreatedEvent(orderId, buyerId, totalAmount));
    }
}
