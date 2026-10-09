package com.ok.order.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "장바구니 담기 응답")
public record AddCartItemResponse(

        @Schema(description = "추가된 장바구니 상품 ID")
        Long cartItemId
) {
}
