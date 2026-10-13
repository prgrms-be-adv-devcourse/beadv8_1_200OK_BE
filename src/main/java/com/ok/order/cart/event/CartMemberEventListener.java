package com.ok.order.cart.event;

import com.ok.member.MemberRegisteredEvent;
import com.ok.order.cart.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CartMemberEventListener {
    private final CartService cartService;

    @ApplicationModuleListener
    void on(MemberRegisteredEvent event) {
        cartService.createCart(event.memberId());
    }
}
