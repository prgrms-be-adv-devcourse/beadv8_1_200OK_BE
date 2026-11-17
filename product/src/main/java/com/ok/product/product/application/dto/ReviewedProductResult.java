package com.ok.product.product.application.dto;

/**
 * 리뷰 하위 도메인이 리뷰를 보여 주고 분석할 때 쓰는 상품 정보.
 */
public record ReviewedProductResult(
	String name,
	ProductCategoryCode category
) {
}
