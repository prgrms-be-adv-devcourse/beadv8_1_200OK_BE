package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductStatus;

// API로 주고받는 판매 상태 값. 컨트롤러가 도메인 enum을 알지 않도록 서비스 경계에서 바꾼다 (상수 이름은 도메인과 같아야 한다)
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
