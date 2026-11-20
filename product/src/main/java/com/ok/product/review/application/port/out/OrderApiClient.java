package com.ok.product.review.application.port.out;

import java.util.List;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.application.dto.ReviewablePurchase;

/**
 * 주문 모듈에서 리뷰를 쓸 수 있는 구매 건을 가져오는 출력 포트. 구현은 {@code adapter.out.order}에 있다.
 */
public interface OrderApiClient {

	/**
	 * 회원이 이 상품을 사서 구매확정한 주문 상품 목록을 가져온다. 없으면 빈 목록이다.
	 *
	 * @throws RestApiException {@code ORDER_API_FAILED} 주문 모듈이 응답하지 않거나 오류를 돌려주면
	 */
	List<ReviewablePurchase> findReviewablePurchases(Long memberId, Long productId);

}
