package com.ok.product.review.adapter.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ok.product.review.application.port.out.ReviewSentimentRepository;
import com.ok.product.review.domain.ReviewSentiment;
import com.ok.product.review.application.dto.ReviewAspectSentimentCount;
import com.ok.product.review.application.dto.ReviewSentimentCount;

public interface ReviewSentimentJpaRepository extends JpaRepository<ReviewSentiment, Long>, ReviewSentimentRepository {

	// JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
	// 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
	@Override
	<S extends ReviewSentiment> S save(S reviewSentiment);

	// 상품의 분석 완료 리뷰를 전체 감성별로 센다. 분석 완료는 삭제되지 않았고 결과가 리뷰의 현재 수정 시각으로 낸 것인 리뷰다.
	// 수정 뒤 재분석 전인 리뷰는 수정 전 결과가 남아 있어도 세지 않는다
	@Override
	@Query("""
			SELECT new com.ok.product.review.application.dto.ReviewSentimentCount(s.overall, COUNT(s))
			FROM ReviewSentiment s
			JOIN ProductReview r ON r.id = s.reviewId
			WHERE r.productId = :productId AND r.deletedAt IS NULL AND s.reviewUpdatedAt = r.updatedAt
			GROUP BY s.overall
			""")
	List<ReviewSentimentCount> countOverallByProductId(Long productId);

	// 상품의 분석 완료 리뷰(전체 감성 집계와 같은 조건)를 분석 항목·감성별로 센다. 언급 없음은 행이 없어 세지 않는다
	@Override
	@Query("""
			SELECT new com.ok.product.review.application.dto.ReviewAspectSentimentCount(a.aspect, a.sentiment, COUNT(a))
			FROM ReviewAspectSentiment a
			JOIN a.reviewSentiment s
			JOIN ProductReview r ON r.id = s.reviewId
			WHERE r.productId = :productId AND r.deletedAt IS NULL AND s.reviewUpdatedAt = r.updatedAt
			GROUP BY a.aspect, a.sentiment
			""")
	List<ReviewAspectSentimentCount> countAspectsByProductId(Long productId);
}
