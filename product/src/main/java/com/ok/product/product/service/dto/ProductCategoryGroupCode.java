package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductCategoryGroup;

// API로 주고받는 상위 카테고리 값
public enum ProductCategoryGroupCode {
	AGRICULTURE;

	public ProductCategoryGroup toDomain() {
		return switch (this) {
			case AGRICULTURE -> ProductCategoryGroup.AGRICULTURE;
		};
	}

	public static ProductCategoryGroupCode from(ProductCategoryGroup group) {
		return switch (group) {
			case AGRICULTURE -> AGRICULTURE;
		};
	}
}
