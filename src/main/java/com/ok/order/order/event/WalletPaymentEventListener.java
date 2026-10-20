package com.ok.order.order.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.common.event.WalletPaymentFailedEvent;
import com.ok.order.order.service.OrderFacade;
import com.ok.order.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WalletPaymentEventListener {

    private final OrderService orderService;
    private final OrderFacade orderFacade;

    @ApplicationModuleListener
    void on(WalletPaymentCompletedEvent event) {
        orderService.completePayment(event.orderId());
    }

    @ApplicationModuleListener
    void on(WalletPaymentFailedEvent event) {
        orderFacade.failPayment(event.orderId(), event.reason());
    }
}
