package com.ok.product.review.controller.dto;

import java.util.List;

import com.ok.product.review.service.dto.ProductReviewCreateCommand;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "상품 리뷰 작성 요청")
public record ProductReviewCreateRequest(

	@Schema(description = "구매한 옵션 ID", example = "12")
	@NotNull(message = "옵션 ID는 필수입니다.")
	@Positive(message = "옵션 ID는 양수여야 합니다.")
	Long productOptionId,

	@Schema(description = "주문 ID. 한 주문에서 같은 상품은 옵션이 달라도 리뷰 1개", example = "100")
	@NotNull(message = "주문 ID는 필수입니다.")
	@Positive(message = "주문 ID는 양수여야 합니다.")
	Long orderId,

	@Schema(description = "주문상품 ID", example = "345")
	@NotNull(message = "주문상품 ID는 필수입니다.")
	@Positive(message = "주문상품 ID는 양수여야 합니다.")
	Long orderItemId,

	@Schema(description = "별점. 0.5점 단위를 정수로 보낸다 (1~10, 7 = 3.5점)", example = "9")
	@NotNull(message = "별점은 필수입니다.")
	@Min(value = 1, message = "별점은 1~10이어야 합니다.")
	@Max(value = 10, message = "별점은 1~10이어야 합니다.")
	Integer rating,

	@Schema(description = "리뷰 내용 (2,000자 이하)", example = "달고 맛있어요")
	@NotBlank(message = "리뷰 내용은 필수입니다.")
	@Size(max = 2000, message = "리뷰 내용은 2,000자 이하여야 합니다.")
	String content,

	@Schema(description = "첨부 URL (0~6개, 영상은 1개까지). 미디어 업로드 API(PRODUCT_REVIEW)로 받은 URL만 허용하고, jpg는 이미지, mp4는 영상으로 저장한다")
	@Size(max = 6, message = "첨부는 6개까지 올릴 수 있습니다.")
	List<@NotBlank(message = "첨부 URL은 비어 있을 수 없습니다.") String> mediaUrls
) {

	// 첨부는 선택이라 생략하면 빈 목록으로 본다
	public ProductReviewCreateRequest {
		mediaUrls = mediaUrls == null ? List.of() : mediaUrls;
	}

	public ProductReviewCreateCommand toCommand() {
		return new ProductReviewCreateCommand(productOptionId, orderId, orderItemId, rating, content, mediaUrls);
	}
}
