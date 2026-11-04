package com.ok.product.review.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ok.product.review.domain.ReviewSentiment;

public interface ReviewSentimentRepository extends JpaRepository<ReviewSentiment, Long> {

	// 상품의 분석 완료 리뷰(삭제되지 않았고 결과가 있는 리뷰)를 전체 감성별로 센다
	@Query("""
			SELECT new com.ok.product.review.repository.ReviewSentimentCount(s.overall, COUNT(s))
			FROM ReviewSentiment s
			JOIN ProductReview r ON r.id = s.reviewId
			WHERE r.productId = :productId AND r.deletedAt IS NULL
			GROUP BY s.overall
			""")
	List<ReviewSentimentCount> countOverallByProductId(Long productId);

	// 상품의 분석 완료 리뷰를 분석 항목·감성별로 센다. 언급 없음은 행이 없어 세지 않는다
	@Query("""
			SELECT new com.ok.product.review.repository.ReviewAspectSentimentCount(a.aspect, a.sentiment, COUNT(a))
			FROM ReviewAspectSentiment a
			JOIN a.reviewSentiment s
			JOIN ProductReview r ON r.id = s.reviewId
			WHERE r.productId = :productId AND r.deletedAt IS NULL
			GROUP BY a.aspect, a.sentiment
			""")
	List<ReviewAspectSentimentCount> countAspectsByProductId(Long productId);
}
