package com.ok.product.review.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.application.dto.ProductReviewKeywordsResult;

/**
 * 구매자에게 상품 리뷰에서 많이 나온 키워드를 보여 주는 유스케이스.
 */
public interface ProductReviewKeywordUseCase {

	/**
	 * 구매자에게 보이는 상품의 리뷰 키워드를 집계한다.
	 * 분석 완료 리뷰가 10개 이상일 때만 리뷰 3개 이상이 표현한 키워드를 많은 순으로 최대 10개 고르고, 못 미치면 키워드는 비운다.
	 * 현재 목록에 있고 상품의 현재 분류에 묻는 키워드만 센다. 문구를 고치거나 키워드를 빼면 이미 분석한 리뷰에도 바로 반영된다.
	 *
	 * @throws RestApiException {@code PRODUCT_NOT_FOUND} 상품이 없거나 숨겨졌거나 삭제됐으면
	 */
	ProductReviewKeywordsResult getKeywords(Long productId);

}
