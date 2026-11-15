package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductVisibility;

// API로 주고받는 노출 여부 값
public enum ProductVisibilityCode {
	VISIBLE,
	HIDDEN;

	public ProductVisibility toDomain() {
		return switch (this) {
			case VISIBLE -> ProductVisibility.VISIBLE;
			case HIDDEN -> ProductVisibility.HIDDEN;
		};
	}

	public static ProductVisibilityCode from(ProductVisibility visibility) {
		return switch (visibility) {
			case VISIBLE -> VISIBLE;
			case HIDDEN -> HIDDEN;
		};
	}
}
