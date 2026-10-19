package com.ok.product.review.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 리뷰 작성 응답")
public record ProductReviewCreateResponse(

	@Schema(description = "작성된 리뷰 ID", example = "1")
	Long reviewId
) {
}
