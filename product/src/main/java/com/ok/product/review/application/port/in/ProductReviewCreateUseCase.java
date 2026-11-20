package com.ok.product.review.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.application.dto.ProductReviewCreateCommand;

/**
 * 구매자가 구매확정한 상품에 리뷰를 쓰는 유스케이스.
 */
public interface ProductReviewCreateUseCase {

	/**
	 * 본인이 구매확정한 주문 상품인지 확인한 뒤 리뷰를 작성한다.
	 *
	 * @throws RestApiException {@code PURCHASE_NOT_REVIEWABLE} 본인 주문이 아니거나, 이 상품의 주문 상품이 아니거나, 구매확정 전이면
	 * @throws RestApiException {@code ORDER_API_FAILED} 주문 모듈이 응답하지 않거나 오류를 돌려주면
	 */
	Long create(Long memberId, Long productId, ProductReviewCreateCommand command);

}
