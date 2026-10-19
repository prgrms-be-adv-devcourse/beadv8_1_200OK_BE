package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductVisibility;

// API로 주고받는 노출 여부 값. 컨트롤러가 도메인 enum을 알지 않도록 서비스 경계에서 바꾼다 (상수 이름은 도메인과 같아야 한다)
public enum ProductVisibilityCode {
	VISIBLE,
	HIDDEN;

	public ProductVisibility toDomain() {
		return ProductVisibility.valueOf(name());
	}

	public static ProductVisibilityCode from(ProductVisibility visibility) {
		return valueOf(visibility.name());
	}
}
