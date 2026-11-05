package com.ok.product.review.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ok.product.review.domain.ReviewKeyword;

public interface ReviewKeywordRepository extends JpaRepository<ReviewKeyword, Long> {

	Optional<ReviewKeyword> findByReviewId(Long reviewId);

	// 상품의 키워드 분석 완료 리뷰 수. 분석 완료는 삭제되지 않았고 결과가 리뷰의 현재 수정 시각으로 낸 것인 리뷰다.
	// 수정 뒤 재분석 전인 리뷰는 수정 전 결과가 남아 있어도 세지 않는다
	@Query("""
			SELECT COUNT(k)
			FROM ReviewKeyword k
			JOIN ProductReview r ON r.id = k.reviewId
			WHERE r.productId = :productId AND r.deletedAt IS NULL AND k.reviewUpdatedAt = r.updatedAt
			""")
	long countAnalyzedByProductId(Long productId);

	// 상품의 분석 완료 리뷰(분석 완료 리뷰 수와 같은 조건)를 키워드별로 센다.
	// 현재 목록과 상품 분류로 거르는 일은 서비스가 한다. 목록이 DB가 아니라 리소스 파일에 있기 때문이다
	@Query("""
			SELECT new com.ok.product.review.repository.ReviewKeywordCount(e.keywordId, COUNT(e))
			FROM ReviewExpressedKeyword e
			JOIN e.reviewKeyword k
			JOIN ProductReview r ON r.id = k.reviewId
			WHERE r.productId = :productId AND r.deletedAt IS NULL AND k.reviewUpdatedAt = r.updatedAt
			GROUP BY e.keywordId
			""")
	List<ReviewKeywordCount> countExpressedByProductId(Long productId);
}
