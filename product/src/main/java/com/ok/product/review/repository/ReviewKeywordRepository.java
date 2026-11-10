package com.ok.product.review.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ok.product.review.domain.ProductReview;
import com.ok.product.review.domain.ReviewKeyword;

public interface ReviewKeywordRepository extends JpaRepository<ReviewKeyword, Long> {

	// 상품의 분석 완료 리뷰를 정한다. 분석 완료는 삭제되지 않았고 결과가 리뷰의 현재 수정 시각으로 낸 것인 리뷰다.
	// 수정 뒤 재분석 전인 리뷰는 수정 전 결과가 남아 있어도 빠진다.
	// 키워드 집계와 키워드 필터가 이 조건을 함께 써야 칩에 보인 리뷰 수와 칩을 눌러 나온 리뷰 수가 같다
	String ANALYZED_REVIEW_OF_PRODUCT = """
			JOIN ProductReview r ON r.id = k.reviewId
			WHERE r.productId = :productId AND r.deletedAt IS NULL AND k.reviewUpdatedAt = r.updatedAt
			""";

	Optional<ReviewKeyword> findByReviewId(Long reviewId);

	@Query("""
			SELECT COUNT(k)
			FROM ReviewKeyword k
			""" + ANALYZED_REVIEW_OF_PRODUCT)
	long countAnalyzedByProductId(Long productId);

	// 상품의 분석 완료 리뷰를 키워드별로 센다.
	// 현재 목록과 상품 분류로 거르는 일은 서비스가 한다. 목록이 DB가 아니라 리소스 파일에 있기 때문이다
	@Query("""
			SELECT new com.ok.product.review.repository.ReviewKeywordCount(e.keywordId, COUNT(e))
			FROM ReviewExpressedKeyword e
			JOIN e.reviewKeyword k
			""" + ANALYZED_REVIEW_OF_PRODUCT + """
			GROUP BY e.keywordId
			""")
	List<ReviewKeywordCount> countExpressedByProductId(Long productId);

	// 상품의 분석 완료 리뷰 중 키워드를 표현한 리뷰를 최신순으로 조회한다.
	// 리뷰마다 결과는 한 행이고 결과 안에서 키워드는 겹치지 않아, 표현한 키워드 행 수가 곧 리뷰 수다
	@Query(value = """
			SELECT r
			FROM ReviewExpressedKeyword e
			JOIN e.reviewKeyword k
			""" + ANALYZED_REVIEW_OF_PRODUCT + """
			AND e.keywordId = :keywordId
			ORDER BY r.id DESC
			""",
		countQuery = """
			SELECT COUNT(e)
			FROM ReviewExpressedKeyword e
			JOIN e.reviewKeyword k
			""" + ANALYZED_REVIEW_OF_PRODUCT + """
			AND e.keywordId = :keywordId
			""")
	Page<ProductReview> findAnalyzedByProductIdAndKeywordId(Long productId, String keywordId, Pageable pageable);
}
