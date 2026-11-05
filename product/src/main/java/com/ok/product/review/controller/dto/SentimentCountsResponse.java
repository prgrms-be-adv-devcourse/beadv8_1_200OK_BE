package com.ok.product.review.controller.dto;

import com.ok.product.review.service.dto.SentimentCountsResult;

import io.swagger.v3.oas.annotations.media.Schema;

public record SentimentCountsResponse(
	@Schema(description = "긍정 리뷰 수", example = "12")
	long positive,

	@Schema(description = "부정 리뷰 수", example = "3")
	long negative,

	@Schema(description = "중립 리뷰 수", example = "2")
	long neutral,

	@Schema(description = "혼합 리뷰 수", example = "1")
	long mixed
) {

	public static SentimentCountsResponse from(SentimentCountsResult result) {
		return new SentimentCountsResponse(result.positive(), result.negative(), result.neutral(), result.mixed());
	}
}
