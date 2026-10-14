package com.ok.product.product.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 상위 카테고리
@Getter
@RequiredArgsConstructor
public enum ProductCategoryGroup {
	AGRICULTURE("농산물");
	// PLANT("식물")

	private final String description;
}
