package com.ok.product.review.controller.dto;

import java.util.List;

import com.ok.product.review.service.dto.ProductReviewKeywordsResult;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProductReviewKeywordsResponse(
	@Schema(description = "키워드 분석 완료 리뷰 수. 10개 미만이라 키워드가 비어도 내려간다", example = "42")
	long analyzedReviewCount,

	@Schema(description = "리뷰 3개 이상이 표현한 키워드. 표현한 리뷰가 많은 순(같으면 키워드 ID 순)으로 최대 10개다. "
		+ "분석 완료 리뷰가 10개 미만이거나 기준을 넘는 키워드가 없으면 빈 배열이다")
	List<ReviewKeywordCountResponse> keywords
) {

	public static ProductReviewKeywordsResponse from(ProductReviewKeywordsResult result) {
		return new ProductReviewKeywordsResponse(result.analyzedReviewCount(),
			result.keywords().stream().map(ReviewKeywordCountResponse::from).toList());
	}
}
