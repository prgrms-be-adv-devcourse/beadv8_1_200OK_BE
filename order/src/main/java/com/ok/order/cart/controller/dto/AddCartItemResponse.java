package com.ok.order.cart.controller.dto;

import com.ok.order.cart.service.dto.AddCartItemResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "장바구니 담기 응답")
public record AddCartItemResponse(

        @Schema(description = "담긴 장바구니 상품 ID (이미 담긴 옵션이면 기존 ID)")
        Long cartItemId,

        @Schema(description = "담은 뒤 수량 (이미 담긴 옵션이면 더한 값, 최대 99)", example = "5")
        int quantity
) {
    public static AddCartItemResponse from(AddCartItemResult result) {
        return new AddCartItemResponse(result.cartItemId(), result.quantity());
    }
}
