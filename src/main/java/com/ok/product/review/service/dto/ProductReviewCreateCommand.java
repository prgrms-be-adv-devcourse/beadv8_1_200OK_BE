package com.ok.product.review.service.dto;

import java.util.List;

public record ProductReviewCreateCommand(
	Long productOptionId,
	Long orderId,
	Long orderItemId,
	int rating,
	String content,
	List<String> mediaUrls // 첨부 타입은 받지 않고 URL 확장자로 정한다
) {
}
