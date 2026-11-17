package com.ok.product.product.application.dto;

public record ProductSearchQuery(
	ProductCategoryGroupCode categoryGroup,
	ProductCategoryCode category,
	ProductRegionCode region,
	int page,
	int size
) {
}
