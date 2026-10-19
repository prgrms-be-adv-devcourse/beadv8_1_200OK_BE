package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductCategory;

// API로 주고받는 하위 카테고리 값
public enum ProductCategoryCode {
	GRAIN,
	VEGETABLE,
	FRUIT,
	ROOT_CROP,
	MUSHROOM,
	NUT,
	WILD_GREENS,
	PROCESSED_AGRI;

	public ProductCategory toDomain() {
		return ProductCategory.valueOf(name());
	}

	public static ProductCategoryCode from(ProductCategory category) {
		return valueOf(category.name());
	}
}
