package com.ok.product.review.controller.dto;

import java.util.List;

import com.ok.product.review.service.dto.ReviewSentimentSummaryResult;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReviewSentimentSummaryResponse(
	@Schema(description = "분석 완료 리뷰 수. 전체 감성의 분모다", example = "18")
	long analyzedReviewCount,

	@Schema(description = "전체 감성별 리뷰 수")
	SentimentCountsResponse overall,

	@Schema(description = "여섯 분석 항목의 감성 집계. 언급한 리뷰가 없는 항목도 0으로 나오고, 순서는 맛·신선도·상품 상태·포장·배송·가격으로 고정이다")
	List<AspectSentimentSummaryResponse> aspects
) {

	public static ReviewSentimentSummaryResponse from(ReviewSentimentSummaryResult result) {
		return new ReviewSentimentSummaryResponse(result.analyzedReviewCount(),
			SentimentCountsResponse.from(result.overall()),
			result.aspects().stream().map(AspectSentimentSummaryResponse::from).toList());
	}
}
