package com.ok.product.review.client.dto;

/**
 * 주문 모듈 내부 API(review-candidates) 응답에서 쓰는 필드만 받는다.
 * 필드 이름은 주문 모듈 응답({@code ReviewCandidateResponse})과 같아야 한다. 나머지 필드는 역직렬화에서 무시된다.
 */
public record OrderReviewCandidateResponse(
	Long orderId,
	Long orderItemId,
	Long productOptionId
) {
}
