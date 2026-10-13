package com.ok.order.cart.controller.dto;

import com.ok.order.cart.domain.Cart;
import com.ok.order.cart.domain.CartItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "장바구니 조회 응답")
public record CartResponse(
        @Schema(description = "장바구니 ID")
        Long cartId,

        @Schema(description = "담긴 상품 목록")
        List<CartItemResponse> items
) {
        public static CartResponse of(Cart cart, List<CartItem> items) {
                return new CartResponse(
                        cart.getId(),
                        items.stream()
                                .map(CartItemResponse::from)
                                .toList()
                );
        }
}
