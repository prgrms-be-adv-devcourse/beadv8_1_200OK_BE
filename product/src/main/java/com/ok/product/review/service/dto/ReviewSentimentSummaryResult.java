package com.ok.product.review.service.dto;

import java.util.List;

/**
 * 상품 하나의 감성 집계다.
 *
 * @param analyzedReviewCount 분석 완료 리뷰 수. 전체 감성의 분모다
 * @param aspects             여섯 분석 항목. 언급한 리뷰가 없어도 고정 순서로 모두 있다
 */
public record ReviewSentimentSummaryResult(
	long analyzedReviewCount,
	SentimentCountsResult overall,
	List<AspectSentimentSummaryResult> aspects
) {
}
