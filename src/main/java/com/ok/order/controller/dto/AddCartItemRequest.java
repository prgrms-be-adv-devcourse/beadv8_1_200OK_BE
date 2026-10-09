package com.ok.order.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "장바구니 담기 요청")
public record AddCartItemRequest(

        @Schema(description = "상품 옵션 ID")
        @NotNull(message = "상품 옵션 ID는 필수입니다.")
        Long productOptionId,

        @Schema(description = "수량")
        @NotNull(message = "수량은 필수입니다.")
        @Min(value = 1, message = "수량은 1개 이상이어야 합니다.")
        Integer quantity
) {
}
