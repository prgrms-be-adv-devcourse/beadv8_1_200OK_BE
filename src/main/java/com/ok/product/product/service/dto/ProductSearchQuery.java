package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;

public record ProductSearchQuery(
	ProductCategoryGroup categoryGroup,
	ProductCategory category,
	ProductRegion region,
	int page,
	int size
) {
}
