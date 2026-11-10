package com.ok.order.cart.adapter.in.web.dto;

import com.ok.order.cart.application.dto.CartItemResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "장바구니 상품")
public record CartItemResponse(

        @Schema(description = "장바구니 상품 ID")
        Long cartItemId,

        @Schema(description = "상품 옵션 ID")
        Long productOptionId,

        @Schema(description = "수량")
        Integer quantity,

        @Schema(description = "주문 선택 여부")
        boolean selected
) {
    public static CartItemResponse from(CartItemResult item) {
        return new CartItemResponse(
                item.cartItemId(),
                item.productOptionId(),
                item.quantity(),
                item.selected()
        );
    }
}
