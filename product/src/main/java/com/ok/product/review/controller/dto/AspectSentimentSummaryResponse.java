package com.ok.product.review.controller.dto;

import com.ok.product.review.service.dto.AspectSentimentSummaryResult;

import io.swagger.v3.oas.annotations.media.Schema;

public record AspectSentimentSummaryResponse(
	@Schema(description = "분석 항목. TASTE(맛), FRESHNESS(신선도), CONDITION(상품 상태), PACKAGING(포장), DELIVERY(배송), PRICE(가격)",
		example = "TASTE")
	String aspect,

	@Schema(description = "항목 분모. 이 항목을 언급한 분석 완료 리뷰 수. 아래 네 범주 개수의 합이다", example = "15")
	long mentionedReviewCount,

	@Schema(description = "이 항목의 감성별 리뷰 수")
	SentimentCountsResponse sentiments
) {

	public static AspectSentimentSummaryResponse from(AspectSentimentSummaryResult result) {
		return new AspectSentimentSummaryResponse(result.aspect(), result.mentionedReviewCount(),
			SentimentCountsResponse.from(result.sentiments()));
	}
}
