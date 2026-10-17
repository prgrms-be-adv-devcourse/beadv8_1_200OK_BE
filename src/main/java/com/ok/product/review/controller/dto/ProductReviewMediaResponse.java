package com.ok.product.review.controller.dto;

import com.ok.product.review.service.dto.ProductReviewMediaResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "리뷰 첨부")
public record ProductReviewMediaResponse(

	@Schema(description = "첨부 종류", example = "IMAGE", allowableValues = {"IMAGE", "VIDEO"})
	String type,

	@Schema(description = "첨부 URL")
	String url
) {

	public static ProductReviewMediaResponse from(ProductReviewMediaResult result) {
		return new ProductReviewMediaResponse(result.type(), result.url());
	}
}
