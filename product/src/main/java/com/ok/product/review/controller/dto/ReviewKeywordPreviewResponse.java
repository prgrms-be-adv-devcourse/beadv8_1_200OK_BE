package com.ok.product.review.controller.dto;

import java.util.List;

import com.ok.product.review.service.dto.ReviewKeywordPreviewResult;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReviewKeywordPreviewResponse(
	@Schema(description = "분석했는지. 내용 없는 본문(글자 없이 기호·숫자·자음·모음만 있음)이면 분석기를 부르지 않고 false다")
	boolean analyzed,

	@Schema(description = "분석에 쓴 키워드 목록 버전. 분석하지 않았으면 null", example = "v0.3")
	String keywordVersion,

	@Schema(description = "분석 모델. 분석하지 않았으면 null", example = "jev-1.14.0")
	String model,

	@Schema(description = "상품 분류에 묻는 키워드 전부(12~30개). 확률이 높은 순(같으면 키워드 ID 순)이다. 분석하지 않았으면 빈 배열이다")
	List<ReviewKeywordJudgementResponse> keywords
) {

	public static ReviewKeywordPreviewResponse from(ReviewKeywordPreviewResult result) {
		return new ReviewKeywordPreviewResponse(result.analyzed(), result.keywordVersion(), result.model(),
			result.keywords().stream().map(ReviewKeywordJudgementResponse::from).toList());
	}
}
