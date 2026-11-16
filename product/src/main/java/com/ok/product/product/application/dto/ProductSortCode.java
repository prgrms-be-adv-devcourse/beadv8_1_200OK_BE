package com.ok.product.product.application.dto;

import com.ok.product.product.domain.ProductSort;

// API로 받는 구매자 상품 목록 정렬 값
public enum ProductSortCode {
	RELEVANCE,
	PRICE_ASC,
	PRICE_DESC,
	LATEST;

	public ProductSort toDomain() {
		return switch (this) {
			case RELEVANCE -> ProductSort.RELEVANCE;
			case PRICE_ASC -> ProductSort.PRICE_ASC;
			case PRICE_DESC -> ProductSort.PRICE_DESC;
			case LATEST -> ProductSort.LATEST;
		};
	}
}
