package com.ok.product.review.application.port.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.application.dto.ProductReviewResult;
import com.ok.product.review.application.dto.ProductReviewUpdateCommand;

/**
 * 구매자가 자기 리뷰를 고치고 지우며, 상품과 내 리뷰를 조회하는 유스케이스.
 */
public interface ProductReviewUseCase {

	/**
	 * 내 리뷰를 수정한다. 별점이나 본문이 바뀌면 같은 트랜잭션에서 모든 분석 종류의 요청을 처음 대기로 되돌린다.
	 * 첨부만 바꾸면 리뷰 행이 바뀌지 않아 수정 시각이 그대로이고 기존 분석 결과도 유효하므로 다시 분석하지 않는다.
	 */
	void update(Long memberId, Long reviewId, ProductReviewUpdateCommand command);

	void delete(Long memberId, Long reviewId);

	Page<ProductReviewResult> getProductReviews(Long productId, Pageable pageable);

	/**
	 * 구매자에게 보이는 상품의 리뷰 중 키워드를 표현한 분석 완료 리뷰를 최신순으로 조회한다.
	 * 키워드 집계와 같은 조건으로 걸러 칩의 리뷰 수와 결과 수가 같다. 리뷰 수가 적어 칩에 뜨지 않는 키워드도 거른다.
	 * 상품의 현재 분류에 묻지 않는 키워드는 집계에서 세지 않으므로 빈 페이지를 돌려준다.
	 *
	 * @throws RestApiException 상품이 없거나 숨겨졌거나 삭제됐으면 {@code PRODUCT_NOT_FOUND},
	 *                          키워드가 현재 목록에 없으면 {@code REVIEW_KEYWORD_NOT_FOUND}
	 */
	Page<ProductReviewResult> getProductReviewsByKeyword(Long productId, String keywordId, Pageable pageable);

	Page<ProductReviewResult> getMyReviews(Long memberId, Pageable pageable);

	List<ProductReviewResult> getMyReviewsOfProduct(Long memberId, Long productId);

}
