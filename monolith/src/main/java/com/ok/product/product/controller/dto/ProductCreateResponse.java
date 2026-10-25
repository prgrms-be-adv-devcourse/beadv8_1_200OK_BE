package com.ok.product.product.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 등록 응답")
public record ProductCreateResponse(

	@Schema(description = "등록된 상품 ID", example = "1")
	Long productId
) {
}
