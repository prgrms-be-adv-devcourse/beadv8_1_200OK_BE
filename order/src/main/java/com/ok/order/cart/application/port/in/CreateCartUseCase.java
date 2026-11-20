package com.ok.order.cart.application.port.in;

/**
 * 회원의 장바구니를 만든다. 회원가입 이벤트를 받을 때 쓴다.
 */
public interface CreateCartUseCase {

    /**
     * 장바구니가 없으면 만들고, 이미 있으면 아무것도 하지 않는다.
     */
    void createCart(Long memberId);
}
