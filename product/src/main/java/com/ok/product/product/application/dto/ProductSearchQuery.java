package com.ok.product.product.application.dto;

/**
 * 구매자 상품 목록 조회 조건.
 *
 * @param keyword 검색어 원문. 정규화는 서비스가 한다. null, 공백, 기호뿐이면 검색어 없이 조회한다
 */
public record ProductSearchQuery(
	String keyword,
	ProductCategoryGroupCode categoryGroup,
	ProductCategoryCode category,
	ProductRegionCode region,
	int page,
	int size
) {
}
