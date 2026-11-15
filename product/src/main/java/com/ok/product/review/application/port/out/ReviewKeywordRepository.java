package com.ok.product.review.application.port.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.ok.product.review.application.dto.ReviewKeywordCount;
import com.ok.product.review.domain.ProductReview;
import com.ok.product.review.domain.ReviewKeyword;

/**
 * 리뷰 키워드 분석 결과 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * 집계와 필터는 같은 "분석 완료 리뷰" 조건을 쓴다. 삭제되지 않았고 결과가 리뷰의 현재 수정 시각으로 낸 리뷰다.
 * 그래야 칩에 보인 리뷰 수와 칩을 눌러 나온 리뷰 수가 같다.
 */
public interface ReviewKeywordRepository {

	Optional<ReviewKeyword> findByReviewId(Long reviewId);

	long countAnalyzedByProductId(Long productId);

	/**
	 * 상품의 분석 완료 리뷰를 키워드별로 센다. 현재 목록과 상품 분류로 거르는 일은 서비스가 한다.
	 */
	List<ReviewKeywordCount> countExpressedByProductId(Long productId);

	/**
	 * 상품의 분석 완료 리뷰 중 키워드를 표현한 리뷰를 최신순으로 조회한다.
	 */
	Page<ProductReview> findAnalyzedByProductIdAndKeywordId(Long productId, String keywordId, Pageable pageable);

	<S extends ReviewKeyword> S save(S reviewKeyword);

}
