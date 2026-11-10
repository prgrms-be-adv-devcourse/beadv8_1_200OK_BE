package com.ok.product.review.controller.dto;

import java.util.Map;

import com.ok.product.review.service.dto.ReviewSentimentPreviewResult;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReviewSentimentPreviewResponse(
	@Schema(description = "분석했는지. 내용 없는 본문(글자 없이 기호·숫자·자음·모음만 있음)이면 분석기를 부르지 않고 false다")
	boolean analyzed,

	@Schema(description = "분석에 쓴 분류 기준 버전. 분석하지 않았으면 null", example = "v0.2")
	String criteriaVersion,

	@Schema(description = "분석 모델. 분석하지 않았으면 null", example = "jev-1.14.0")
	String model,

	@Schema(description = "전체 감성. POSITIVE, NEGATIVE, NEUTRAL, MIXED 중 하나. 분석하지 않았으면 null", example = "POSITIVE")
	String overall,

	@Schema(description = "여섯 분석 항목(TASTE, FRESHNESS, CONDITION, PACKAGING, DELIVERY, PRICE)마다 감성. "
		+ "값은 POSITIVE, NEGATIVE, NEUTRAL, MIXED, NOT_MENTIONED(언급 없음) 중 하나다. 분석하지 않았으면 빈 객체다",
		example = "{\"TASTE\":\"POSITIVE\",\"FRESHNESS\":\"NOT_MENTIONED\",\"CONDITION\":\"NOT_MENTIONED\","
			+ "\"PACKAGING\":\"NOT_MENTIONED\",\"DELIVERY\":\"NEGATIVE\",\"PRICE\":\"NOT_MENTIONED\"}")
	Map<String, String> aspects
) {

	public static ReviewSentimentPreviewResponse from(ReviewSentimentPreviewResult result) {
		return new ReviewSentimentPreviewResponse(result.analyzed(), result.criteriaVersion(), result.model(),
			result.overall(), result.aspects());
	}
}
