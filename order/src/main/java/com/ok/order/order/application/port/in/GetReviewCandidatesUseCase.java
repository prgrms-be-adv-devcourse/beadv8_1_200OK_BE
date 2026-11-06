package com.ok.order.order.application.port.in;

import com.ok.order.order.application.dto.ReviewCandidateResult;

import java.util.List;

/**
 * 리뷰를 쓸 수 있는 구매 건을 조회한다. 상품 모듈의 리뷰 작성에서 쓴다.
 */
public interface GetReviewCandidatesUseCase {

    /**
     * 구매자가 이 상품을 사서 구매확정한 주문 상품을 최신 주문부터 조회한다.
     */
    List<ReviewCandidateResult> getReviewCandidates(Long buyerId, Long productId);
}
