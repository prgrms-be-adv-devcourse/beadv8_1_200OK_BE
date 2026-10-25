package com.ok.order.cart.controller.dto;

import com.ok.order.cart.domain.CartItem;
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
    public static CartItemResponse from(CartItem item) {
        return new CartItemResponse(
                item.getId(),
                item.getProductOptionId(),
                item.getQuantity(),
                item.isSelected()
        );
    }
}
