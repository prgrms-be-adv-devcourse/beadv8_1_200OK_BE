package com.ok.product.review.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ok.product.review.domain.ReviewSentiment;

public interface ReviewSentimentRepository extends JpaRepository<ReviewSentiment, Long> {

	Optional<ReviewSentiment> findByReviewId(Long reviewId);

	// 상품의 분석 완료 리뷰를 전체 감성별로 센다. 분석 완료는 삭제되지 않았고 결과가 리뷰의 현재 수정 시각으로 낸 것인 리뷰다.
	// 수정 뒤 재분석 전인 리뷰는 수정 전 결과가 남아 있어도 세지 않는다
	@Query("""
			SELECT new com.ok.product.review.repository.ReviewSentimentCount(s.overall, COUNT(s))
			FROM ReviewSentiment s
			JOIN ProductReview r ON r.id = s.reviewId
			WHERE r.productId = :productId AND r.deletedAt IS NULL AND s.reviewUpdatedAt = r.updatedAt
			GROUP BY s.overall
			""")
	List<ReviewSentimentCount> countOverallByProductId(Long productId);

	// 상품의 분석 완료 리뷰(전체 감성 집계와 같은 조건)를 분석 항목·감성별로 센다. 언급 없음은 행이 없어 세지 않는다
	@Query("""
			SELECT new com.ok.product.review.repository.ReviewAspectSentimentCount(a.aspect, a.sentiment, COUNT(a))
			FROM ReviewAspectSentiment a
			JOIN a.reviewSentiment s
			JOIN ProductReview r ON r.id = s.reviewId
			WHERE r.productId = :productId AND r.deletedAt IS NULL AND s.reviewUpdatedAt = r.updatedAt
			GROUP BY a.aspect, a.sentiment
			""")
	List<ReviewAspectSentimentCount> countAspectsByProductId(Long productId);
}
