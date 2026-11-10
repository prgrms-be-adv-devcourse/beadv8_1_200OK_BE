package com.ok.order.cart.adapter.in.web.dto;

import com.ok.order.cart.application.dto.CartResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "장바구니 조회 응답")
public record CartResponse(
        @Schema(description = "장바구니 ID")
        Long cartId,

        @Schema(description = "담긴 상품 목록")
        List<CartItemResponse> items
) {
    public static CartResponse from(CartResult result) {
        return new CartResponse(
                result.cartId(),
                result.items().stream()
                        .map(CartItemResponse::from)
                        .toList()
        );
    }
}
