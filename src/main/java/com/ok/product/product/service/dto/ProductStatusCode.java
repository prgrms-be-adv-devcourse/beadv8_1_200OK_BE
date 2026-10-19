package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductStatus;

// API로 주고받는 판매 상태 값
public enum ProductStatusCode {
	READY,
	ON_SALE,
	PAUSED,
	ENDED;

	public ProductStatus toDomain() {
		return ProductStatus.valueOf(name());
	}

	public static ProductStatusCode from(ProductStatus status) {
		return valueOf(status.name());
	}
}
