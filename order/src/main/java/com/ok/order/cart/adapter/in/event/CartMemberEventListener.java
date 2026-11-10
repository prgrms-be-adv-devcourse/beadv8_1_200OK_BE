package com.ok.order.cart.adapter.in.event;

import com.ok.common.event.MemberRegisteredEvent;
import com.ok.order.cart.application.port.in.CreateCartUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CartMemberEventListener {
    private final CreateCartUseCase createCart;

    @ApplicationModuleListener
    void on(MemberRegisteredEvent event) {
        createCart.createCart(event.memberId());
    }
}
