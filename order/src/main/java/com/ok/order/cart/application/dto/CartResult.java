package com.ok.order.cart.application.dto;

import com.ok.order.cart.domain.Cart;
import com.ok.order.cart.domain.CartItem;

import java.util.List;

/**
 * 장바구니 조회 결과. 컨트롤러가 엔티티를 몰라도 되도록 엔티티 대신 값으로 넘긴다.
 */
public record CartResult(Long cartId, List<CartItemResult> items) {

    public static CartResult of(Cart cart, List<CartItem> items) {
        return new CartResult(cart.getId(), items.stream().map(CartItemResult::from).toList());
    }
}
