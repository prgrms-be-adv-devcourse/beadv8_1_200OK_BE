package com.ok.order.order.event;

import com.ok.common.event.ShipmentDeliveredEvent;
import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.order.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShipmentEventListener {

    private final OrderService orderService;

    @ApplicationModuleListener
    void on(ShipmentDispatchedEvent event) {
        orderService.startShipping(event.orderId(), event.sellerId());
    }

    @ApplicationModuleListener
    void on(ShipmentDeliveredEvent event) {
        orderService.completeDelivery(event.orderId(), event.sellerId(), event.deliveredAt());
    }
}
