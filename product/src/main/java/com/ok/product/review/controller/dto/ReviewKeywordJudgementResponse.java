package com.ok.product.review.controller.dto;

import com.ok.product.review.service.dto.ReviewKeywordJudgementResult;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReviewKeywordJudgementResponse(
	@Schema(description = "키워드 ID", example = "C02")
	String keywordId,

	@Schema(description = "키워드 문구", example = "달아요")
	String phrase,

	@Schema(description = "키워드가 속한 분석 항목. TASTE, FRESHNESS, CONDITION, PACKAGING, DELIVERY, PRICE, OTHER(기타) 중 하나", example = "TASTE")
	String aspect,

	@Schema(description = "분석기가 \"말함\"을 고를 확률 (0~1)", example = "0.97")
	double probability,

	@Schema(description = "리뷰가 이 키워드를 표현했다고 판정했는지. 확률 0.9 이상이고 식감 규칙으로 빠지지 않은 키워드다. 운영 작업자가 저장하는 표현한 키워드와 같다")
	boolean expressed,

	@Schema(description = "확률은 0.9 이상이지만 구체적인 식감 키워드가 함께 있어 표현하지 않은 것으로 본 \"식감이 좋아요\"(C25)인지")
	boolean excludedByTextureRule
) {

	public static ReviewKeywordJudgementResponse from(ReviewKeywordJudgementResult result) {
		return new ReviewKeywordJudgementResponse(result.keywordId(), result.phrase(), result.aspect(),
			result.probability(), result.expressed(), result.excludedByTextureRule());
	}
}
