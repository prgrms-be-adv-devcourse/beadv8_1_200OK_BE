package com.ok.product.review.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ok.product.review.domain.ReviewAnalysisRequest;
import com.ok.product.review.domain.ReviewAnalysisStatus;
import com.ok.product.review.domain.ReviewAnalysisType;

public interface ReviewAnalysisRequestRepository extends JpaRepository<ReviewAnalysisRequest, Long> {

	// 작업자가 꺼낼 요청. 오래 기다린 요청부터
	List<ReviewAnalysisRequest> findByAnalysisTypeAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
		ReviewAnalysisType analysisType, ReviewAnalysisStatus status, LocalDateTime now, Limit limit);

	Optional<ReviewAnalysisRequest> findByReviewIdAndAnalysisType(Long reviewId, ReviewAnalysisType analysisType);
}
