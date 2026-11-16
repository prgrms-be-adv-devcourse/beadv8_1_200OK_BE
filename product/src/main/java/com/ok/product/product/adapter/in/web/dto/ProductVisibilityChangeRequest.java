package com.ok.product.product.adapter.in.web.dto;

import com.ok.product.product.application.dto.ProductVisibilityCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "상품 노출 여부 변경 요청")
public record ProductVisibilityChangeRequest(

	@Schema(description = "노출 여부", example = "VISIBLE")
	@NotNull(message = "노출 여부는 필수입니다.")
	ProductVisibilityCode visibility
) {
}
