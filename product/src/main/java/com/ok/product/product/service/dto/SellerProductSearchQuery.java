package com.ok.product.product.service.dto;

// 판매자 본인 상품 목록의 조회 조건. 필터는 모두 선택이다
public record SellerProductSearchQuery(
	ProductStatusCode status,
	ProductVisibilityCode visibility,
	int page,
	int size
) {
}
