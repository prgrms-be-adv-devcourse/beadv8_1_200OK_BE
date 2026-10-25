package com.ok.product.review.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.ok.product.review.service.dto.ProductReviewResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 리뷰")
public record ProductReviewResponse(

	@Schema(description = "리뷰 ID", example = "1")
	Long reviewId,

	@Schema(description = "상품 ID", example = "10")
	Long productId,

	@Schema(description = "구매한 옵션 ID", example = "12")
	Long productOptionId,

	@Schema(description = "구매한 옵션 이름", example = "3kg 특품")
	String optionName,

	@Schema(description = "작성자 회원 ID", example = "1")
	Long memberId,

	@Schema(description = "별점. 0.5점 단위 정수 (1~10, 화면에는 2로 나눠 표시)", example = "9")
	int rating,

	@Schema(description = "리뷰 내용", example = "달고 맛있어요")
	String content,

	@Schema(description = "첨부 (올린 순서)")
	List<ProductReviewMediaResponse> media,

	@Schema(description = "작성 시각")
	LocalDateTime createdAt,

	@Schema(description = "마지막 수정 시각")
	LocalDateTime updatedAt
) {

	public static ProductReviewResponse from(ProductReviewResult result) {
		return new ProductReviewResponse(result.reviewId(), result.productId(), result.productOptionId(),
			result.optionName(), result.memberId(), result.rating(), result.content(),
			result.media().stream().map(ProductReviewMediaResponse::from).toList(),
			result.createdAt(), result.updatedAt());
	}
}
