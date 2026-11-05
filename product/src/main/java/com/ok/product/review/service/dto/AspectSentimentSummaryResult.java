package com.ok.product.review.service.dto;

/**
 * 분석 항목 하나의 감성 집계다.
 *
 * @param aspect               분석 항목 이름 ({@code TASTE} 등)
 * @param mentionedReviewCount 항목 분모. 이 항목을 언급한 분석 완료 리뷰 수
 */
public record AspectSentimentSummaryResult(
	String aspect,
	long mentionedReviewCount,
	SentimentCountsResult sentiments
) {
}
