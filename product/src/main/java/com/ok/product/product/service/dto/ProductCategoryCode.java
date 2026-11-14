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
		return switch (this) {
			case GRAIN -> ProductCategory.GRAIN;
			case VEGETABLE -> ProductCategory.VEGETABLE;
			case FRUIT -> ProductCategory.FRUIT;
			case ROOT_CROP -> ProductCategory.ROOT_CROP;
			case MUSHROOM -> ProductCategory.MUSHROOM;
			case NUT -> ProductCategory.NUT;
			case WILD_GREENS -> ProductCategory.WILD_GREENS;
			case PROCESSED_AGRI -> ProductCategory.PROCESSED_AGRI;
		};
	}

	public static ProductCategoryCode from(ProductCategory category) {
		return switch (category) {
			case GRAIN -> GRAIN;
			case VEGETABLE -> VEGETABLE;
			case FRUIT -> FRUIT;
			case ROOT_CROP -> ROOT_CROP;
			case MUSHROOM -> MUSHROOM;
			case NUT -> NUT;
			case WILD_GREENS -> WILD_GREENS;
			case PROCESSED_AGRI -> PROCESSED_AGRI;
		};
	}
}
