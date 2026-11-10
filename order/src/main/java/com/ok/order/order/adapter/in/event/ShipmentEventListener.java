package com.ok.order.order.adapter.in.event;

import com.ok.common.event.ShipmentDeliveredEvent;
import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.order.order.application.port.in.UpdateShippingStatusUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShipmentEventListener {

    private final UpdateShippingStatusUseCase updateShippingStatus;

    @ApplicationModuleListener
    void on(ShipmentDispatchedEvent event) {
        updateShippingStatus.startShipping(event.orderId(), event.sellerId());
    }

    @ApplicationModuleListener
    void on(ShipmentDeliveredEvent event) {
        updateShippingStatus.completeDelivery(event.orderId(), event.sellerId(), event.deliveredAt());
    }
}
