package com.ok.product.review.service.dto;

/**
 * 주문 모듈이 확인해 준, 리뷰를 쓸 수 있는 구매 건이다.
 * 저장할 주문·옵션 ID는 요청이 아니라 여기서 가져온다. 사용자 입력과 검증된 값을 타입으로 구분하기 위해 Command와 따로 둔다.
 */
public record ReviewablePurchase(
	Long orderId,
	Long orderItemId,
	Long productOptionId
) {
}
