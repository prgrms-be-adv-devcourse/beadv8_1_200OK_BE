package com.ok.product.product.controller.dto;

import java.util.List;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상위 카테고리와 그 하위 카테고리")
public record ProductCategoryGroupResponse(

	@Schema(description = "상위 카테고리 코드. 목록 조회의 categoryGroup 값", example = "AGRICULTURE")
	ProductCategoryGroup group,

	@Schema(description = "상위 카테고리 이름", example = "농산물")
	String description,

	@Schema(description = "하위 카테고리")
	List<ProductCategoryResponse> categories
) {

	public static ProductCategoryGroupResponse from(ProductCategoryGroup group) {
		List<ProductCategoryResponse> categories = ProductCategory.findAllByGroup(group).stream()
			.map(category -> new ProductCategoryResponse(category, category.getDescription()))
			.toList();
		return new ProductCategoryGroupResponse(group, group.getDescription(), categories);
	}
}
