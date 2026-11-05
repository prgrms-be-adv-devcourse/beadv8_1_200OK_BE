package com.ok.product.review.service.dto;

import com.ok.product.product.domain.ProductCategory;

/**
 * 키워드 분석기에 넘기는 리뷰 하나다.
 * 분석기는 상품의 현재 하위 분류에 적용되는 키워드만 묻는다. 옵션명은 정밀도를 잰 실험 조건에 없어 넘기지 않는다.
 * 리뷰 ID는 분석기에 보내지 않고, 선택지 순서를 섞는 시드로만 쓴다.
 */
public record ReviewKeywordInput(
	Long reviewId,
	String productName,
	String content,
	ProductCategory category
) {
}
