package com.ok.product.review.controller.dto;

import com.ok.product.review.service.dto.ReviewAnalysisPreviewQuery;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "리뷰 분석 테스트 요청")
public record ReviewAnalysisPreviewRequest(

	@Schema(description = "상품 ID. 상품명과 분류를 이 상품에서 읽는다. 숨긴 상품도 된다", example = "10")
	@NotNull(message = "상품 ID는 필수입니다.")
	@Positive(message = "상품 ID는 양수여야 합니다.")
	Long productId,

	@Schema(description = "옵션 ID. 그 상품의 옵션이어야 한다. 옵션명은 감성 분석에만 보낸다", example = "12")
	@NotNull(message = "옵션 ID는 필수입니다.")
	@Positive(message = "옵션 ID는 양수여야 합니다.")
	Long optionId,

	// 리뷰 작성과 같은 검증이다. 운영에 들어올 수 없는 본문을 테스트하지 않게 한다
	@Schema(description = "리뷰 본문 (2,000자 이하)", example = "달고 아삭해요")
	@NotBlank(message = "리뷰 내용은 필수입니다.")
	@Size(max = 2000, message = "리뷰 내용은 2,000자 이하여야 합니다.")
	String content
) {

	public ReviewAnalysisPreviewQuery toQuery() {
		return new ReviewAnalysisPreviewQuery(productId, optionId, content);
	}
}
