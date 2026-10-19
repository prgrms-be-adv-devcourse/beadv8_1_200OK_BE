package com.ok.product.review.service.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ProductReviewResult(
	Long reviewId,
	Long productId,
	Long productOptionId,
	String optionName, // 구매한 옵션 표시용. 옵션이 지워져도 이름은 남아 있다
	Long memberId,
	int rating,
	String content,
	List<ProductReviewMediaResult> media,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
