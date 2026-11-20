package com.ok.order.cart.application.dto;

import com.ok.order.cart.domain.CartItem;

/**
 * 장바구니 상품 한 줄의 조회 결과.
 */
public record CartItemResult(Long cartItemId, Long productOptionId, int quantity, boolean selected) {

    public static CartItemResult from(CartItem item) {
        return new CartItemResult(item.getId(), item.getProductOptionId(), item.getQuantity(), item.isSelected());
    }
}
