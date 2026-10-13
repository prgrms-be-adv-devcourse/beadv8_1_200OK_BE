package com.ok.order.cart.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "장바구니 상품 선택 변경 요청")
public record UpdateCartItemSelectionRequest(

        @Schema(description = "선택 여부 (true: 선택, false: 해제)")
        @NotNull(message = "선택 여부는 필수입니다.")
        Boolean selected
) {
}