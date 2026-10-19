package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductCategory;

public record ProductCategoryResult(
	ProductCategory code,
	String description
) {

	public static ProductCategoryResult from(ProductCategory category) {
		return new ProductCategoryResult(category, category.getDescription());
	}
}
