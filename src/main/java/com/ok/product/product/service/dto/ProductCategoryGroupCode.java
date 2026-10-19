package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductCategoryGroup;

// API로 주고받는 상위 카테고리 값. 컨트롤러가 도메인 enum을 알지 않도록 서비스 경계에서 바꾼다 (상수 이름은 도메인과 같아야 한다)
public enum ProductCategoryGroupCode {
	AGRICULTURE;

	public ProductCategoryGroup toDomain() {
		return ProductCategoryGroup.valueOf(name());
	}

	public static ProductCategoryGroupCode from(ProductCategoryGroup group) {
		return valueOf(group.name());
	}
}
