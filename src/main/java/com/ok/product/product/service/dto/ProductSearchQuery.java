package com.ok.product.product.service.dto;

public record ProductSearchQuery(
	ProductCategoryGroupCode categoryGroup,
	ProductCategoryCode category,
	ProductRegionCode region,
	int page,
	int size
) {
}
