package com.ok.product.review.application.port.out;

import java.util.List;
import java.util.Optional;

import com.ok.product.review.application.dto.ReviewAspectSentimentCount;
import com.ok.product.review.application.dto.ReviewSentimentCount;
import com.ok.product.review.domain.ReviewSentiment;

/**
 * 리뷰 감성 분석 결과 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * 집계는 분석 완료 리뷰만 센다. 삭제되지 않았고 결과가 리뷰의 현재 수정 시각으로 낸 리뷰다.
 */
public interface ReviewSentimentRepository {

	Optional<ReviewSentiment> findByReviewId(Long reviewId);

	List<ReviewSentimentCount> countOverallByProductId(Long productId);

	// 언급 없음은 행이 없어 세지 않는다
	List<ReviewAspectSentimentCount> countAspectsByProductId(Long productId);

	<S extends ReviewSentiment> S save(S reviewSentiment);

}
