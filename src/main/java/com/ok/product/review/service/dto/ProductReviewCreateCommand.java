package com.ok.product.review.service.dto;

import java.util.List;

public record ProductReviewCreateCommand(
	Long orderItemId, // 주문·옵션 ID는 받지 않는다. 주문 모듈이 확인해 준 구매 건(ReviewablePurchase)에서 가져온다
	int rating,
	String content,
	List<String> mediaUrls // 첨부 타입은 받지 않고 URL 확장자로 정한다
) {
}
