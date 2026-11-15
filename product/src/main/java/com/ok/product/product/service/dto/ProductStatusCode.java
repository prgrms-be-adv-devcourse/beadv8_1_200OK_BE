package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductStatus;

// API로 주고받는 판매 상태 값
public enum ProductStatusCode {
	READY,
	ON_SALE,
	PAUSED,
	ENDED;

	public ProductStatus toDomain() {
		return switch (this) {
			case READY -> ProductStatus.READY;
			case ON_SALE -> ProductStatus.ON_SALE;
			case PAUSED -> ProductStatus.PAUSED;
			case ENDED -> ProductStatus.ENDED;
		};
	}

	public static ProductStatusCode from(ProductStatus status) {
		return switch (status) {
			case READY -> READY;
			case ON_SALE -> ON_SALE;
			case PAUSED -> PAUSED;
			case ENDED -> ENDED;
		};
	}
}
