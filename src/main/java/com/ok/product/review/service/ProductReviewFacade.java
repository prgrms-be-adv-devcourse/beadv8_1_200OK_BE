package com.ok.product.review.service;

import org.springframework.stereotype.Component;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.client.OrderApiClient;
import com.ok.product.review.exception.ProductReviewErrorCode;
import com.ok.product.review.service.dto.ProductReviewCreateCommand;
import com.ok.product.review.service.dto.ReviewablePurchase;

import lombok.RequiredArgsConstructor;

/**
 * 리뷰 작성 전에 주문 모듈로 작성 자격을 확인한다.
 * 주문 모듈 호출은 HTTP라 트랜잭션 밖(여기)에서 하고, 저장은 {@link ProductReviewService}의 트랜잭션에서 한다.
 */
@Component
@RequiredArgsConstructor
public class ProductReviewFacade {

	private final OrderApiClient orderApiClient;
	private final ProductReviewService productReviewService;

	/**
	 * 본인이 구매확정한 주문 상품인지 확인한 뒤 리뷰를 작성한다.
	 *
	 * @throws RestApiException {@code PURCHASE_NOT_REVIEWABLE} 본인 주문이 아니거나, 이 상품의 주문 상품이 아니거나, 구매확정 전이면
	 * @throws RestApiException {@code ORDER_API_FAILED} 주문 모듈이 응답하지 않거나 오류를 돌려주면
	 */
	public Long create(Long memberId, Long productId, ProductReviewCreateCommand command) {
		ReviewablePurchase purchase = findReviewablePurchase(memberId, productId, command.orderItemId());
		return productReviewService.create(memberId, productId, purchase, command);
	}

	private ReviewablePurchase findReviewablePurchase(Long memberId, Long productId, Long orderItemId) {
		return orderApiClient.findReviewablePurchases(memberId, productId).stream()
			.filter(purchase -> purchase.orderItemId().equals(orderItemId))
			.findFirst()
			.orElseThrow(() -> new RestApiException(ProductReviewErrorCode.PURCHASE_NOT_REVIEWABLE));
	}
}
