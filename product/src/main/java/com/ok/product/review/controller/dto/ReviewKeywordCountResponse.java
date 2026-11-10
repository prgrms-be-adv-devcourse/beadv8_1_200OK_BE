package com.ok.product.review.controller.dto;

import com.ok.product.review.service.dto.ReviewKeywordCountResult;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReviewKeywordCountResponse(
	@Schema(description = "키워드 ID. 리뷰 목록을 이 키워드로 거를 때 쓴다", example = "C02")
	String keywordId,

	@Schema(description = "화면에 보여 줄 키워드 문구", example = "달아요")
	String phrase,

	@Schema(description = "키워드가 속한 분석 항목. TASTE, FRESHNESS, CONDITION, PACKAGING, DELIVERY, PRICE, OTHER(기타) 중 하나", example = "TASTE")
	String aspect,

	@Schema(description = "이 키워드를 표현한 리뷰 수", example = "17")
	long reviewCount
) {

	public static ReviewKeywordCountResponse from(ReviewKeywordCountResult result) {
		return new ReviewKeywordCountResponse(result.keywordId(), result.phrase(), result.aspect(), result.reviewCount());
	}
}
