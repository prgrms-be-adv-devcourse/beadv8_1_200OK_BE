package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductCategory;

public record ProductCategoryResult(
	ProductCategoryCode code,
	String description
) {

	public static ProductCategoryResult from(ProductCategory category) {
		return new ProductCategoryResult(ProductCategoryCode.from(category), category.getDescription());
	}
}
