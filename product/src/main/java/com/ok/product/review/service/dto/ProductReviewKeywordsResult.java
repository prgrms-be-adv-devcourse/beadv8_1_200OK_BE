package com.ok.product.review.service.dto;

import java.util.List;

/**
 * 상품 하나의 리뷰 키워드 집계다.
 *
 * @param analyzedReviewCount 키워드 분석 완료 리뷰 수. 노출 기준(10개)에 못 미쳐 키워드가 비어도 그대로 담는다
 * @param keywords            노출할 키워드. 표현한 리뷰가 많은 순이고 같으면 키워드 ID 순이다
 */
public record ProductReviewKeywordsResult(
	long analyzedReviewCount,
	List<ReviewKeywordCountResult> keywords
) {
}
