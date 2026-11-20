package com.ok.order.cart.application.port.in;

import com.ok.order.cart.application.dto.CartResult;

/**
 * 내 장바구니를 조회한다.
 */
public interface GetCartUseCase {

    /**
     * 내 장바구니와 담긴 상품을 조회한다. 장바구니가 없으면 만든다.
     */
    CartResult getMyCart(Long memberId);
}
