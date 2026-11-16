package com.ok.product.product.adapter.in.web.dto;

import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ProductCategoryResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "하위 카테고리")
public record ProductCategoryResponse(

	@Schema(description = "하위 카테고리 코드. 목록 조회의 category 값", example = "FRUIT")
	ProductCategoryCode code,

	@Schema(description = "하위 카테고리 이름", example = "과일")
	String description
) {

	public static ProductCategoryResponse from(ProductCategoryResult result) {
		return new ProductCategoryResponse(result.code(), result.description());
	}
}
