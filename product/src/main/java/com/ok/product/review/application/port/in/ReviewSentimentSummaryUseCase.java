package com.ok.product.review.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.application.dto.ReviewSentimentSummaryResult;

/**
 * 판매자에게 자기 상품 리뷰의 감성 집계를 보여 주는 유스케이스.
 */
public interface ReviewSentimentSummaryUseCase {

	/**
	 * 판매자 상품의 분석 완료 리뷰를 전체 감성과 분석 항목별로 센다.
	 * 분석을 기다리거나 최종 실패한 리뷰는 결과가 없어 분모와 분자 어디에도 들지 않는다.
	 *
	 * @throws RestApiException {@code PRODUCT_NOT_FOUND} 상품이 없거나 삭제됐거나 내 상품이 아니면
	 */
	ReviewSentimentSummaryResult getSummary(Long sellerId, Long productId);

}
