package com.ok.product.review.service.dto;

/**
 * 관리자가 리뷰를 남기지 않고 분석해 보는 본문 하나와, 운영 작업자와 같은 입력을 만들 상품·옵션이다.
 */
public record ReviewAnalysisPreviewQuery(
	Long productId,
	Long optionId,
	String content
) {
}
