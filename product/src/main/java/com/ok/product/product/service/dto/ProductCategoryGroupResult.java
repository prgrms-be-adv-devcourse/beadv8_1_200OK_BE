package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;

import java.util.List;

public record ProductCategoryGroupResult(
	ProductCategoryGroupCode group,
	String description,
	List<ProductCategoryResult> categories
) {

	public static ProductCategoryGroupResult from(ProductCategoryGroup group) {
		List<ProductCategoryResult> categories = ProductCategory.findAllByGroup(group).stream()
			.map(ProductCategoryResult::from)
			.toList();
		return new ProductCategoryGroupResult(ProductCategoryGroupCode.from(group), group.getDescription(), categories);
	}
}
