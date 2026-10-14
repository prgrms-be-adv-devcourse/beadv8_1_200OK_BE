package com.ok.product.product.controller.dto;

import com.ok.product.product.domain.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "상품 판매 상태 변경 요청")
public record ProductStatusChangeRequest(

	@Schema(description = "판매 상태", example = "ON_SALE")
	@NotNull(message = "판매 상태는 필수입니다.")
	ProductStatus status
) {
}
