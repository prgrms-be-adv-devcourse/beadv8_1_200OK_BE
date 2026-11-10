package com.ok.order.order.adapter.in.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.common.event.WalletPaymentFailedEvent;
import com.ok.order.order.application.port.in.CompletePaymentUseCase;
import com.ok.order.order.application.port.in.FailPaymentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WalletPaymentEventListener {

    private final CompletePaymentUseCase completePayment;
    private final FailPaymentUseCase failPayment;

    @ApplicationModuleListener
    void on(WalletPaymentCompletedEvent event) {
        completePayment.completePayment(event.orderId());
    }

    @ApplicationModuleListener
    void on(WalletPaymentFailedEvent event) {
        failPayment.failPayment(event.orderId(), event.reason());
    }
}
