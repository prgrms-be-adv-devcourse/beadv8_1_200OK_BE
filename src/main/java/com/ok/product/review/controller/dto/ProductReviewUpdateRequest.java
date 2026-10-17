package com.ok.product.review.controller.dto;

import java.util.List;

import com.ok.product.review.service.dto.ProductReviewUpdateCommand;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "상품 리뷰 수정 요청")
public record ProductReviewUpdateRequest(

	@Schema(description = "별점. 0.5점 단위를 정수로 보낸다 (1~10, 7 = 3.5점)", example = "9")
	@NotNull(message = "별점은 필수입니다.")
	@Min(value = 1, message = "별점은 1~10이어야 합니다.")
	@Max(value = 10, message = "별점은 1~10이어야 합니다.")
	Integer rating,

	@Schema(description = "리뷰 내용 (2,000자 이하)", example = "다시 먹어도 맛있어요")
	@NotBlank(message = "리뷰 내용은 필수입니다.")
	@Size(max = 2000, message = "리뷰 내용은 2,000자 이하여야 합니다.")
	String content,

	@Schema(description = "첨부 URL (0~6개, 영상은 1개까지). 보낸 목록으로 전체 교체한다")
	@Size(max = 6, message = "첨부는 6개까지 올릴 수 있습니다.")
	List<@NotBlank(message = "첨부 URL은 비어 있을 수 없습니다.") String> mediaUrls
) {

	// 첨부는 선택이라 생략하면 빈 목록(첨부 전부 삭제)으로 본다
	public ProductReviewUpdateRequest {
		mediaUrls = mediaUrls == null ? List.of() : mediaUrls;
	}

	public ProductReviewUpdateCommand toCommand() {
		return new ProductReviewUpdateCommand(rating, content, mediaUrls);
	}
}
