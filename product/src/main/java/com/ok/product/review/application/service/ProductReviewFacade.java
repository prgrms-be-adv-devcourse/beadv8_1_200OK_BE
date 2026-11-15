package com.ok.product.review.application.service;

import org.springframework.stereotype.Component;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.application.port.out.OrderApiClient;
import com.ok.product.review.exception.ProductReviewErrorCode;
import com.ok.product.review.application.dto.ProductReviewCreateCommand;
import com.ok.product.review.application.dto.ReviewablePurchase;
import com.ok.product.review.application.port.in.ProductReviewCreateUseCase;

import lombok.RequiredArgsConstructor;

/**
 * 리뷰 작성 전에 주문 모듈로 작성 자격을 확인한다.
 * 주문 모듈 호출은 HTTP라 트랜잭션 밖(여기)에서 하고, 저장은 {@link ProductReviewService}의 트랜잭션에서 한다.
 */
@Component
@RequiredArgsConstructor
public class ProductReviewFacade implements ProductReviewCreateUseCase {

	private final OrderApiClient orderApiClient;
	private final ProductReviewService productReviewService;

	@Override
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
