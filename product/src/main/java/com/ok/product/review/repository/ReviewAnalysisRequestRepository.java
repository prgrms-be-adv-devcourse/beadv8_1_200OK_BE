package com.ok.product.review.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import com.ok.product.review.domain.ReviewAnalysisRequest;
import com.ok.product.review.domain.ReviewAnalysisStatus;
import com.ok.product.review.domain.ReviewAnalysisType;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface ReviewAnalysisRequestRepository extends JpaRepository<ReviewAnalysisRequest, Long> {

	// 작업자가 꺼낼 요청. 오래 기다린 요청부터.
	// 다른 작업자가 꺼내는 중인 행은 기다리지 않고 건너뛴다(timeout -2 = SKIP LOCKED). 잠금만으로는 커밋 뒤에 다시 꺼내지므로 꺼낸 요청은 claim으로 미룬다
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	List<ReviewAnalysisRequest> findByAnalysisTypeAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
		ReviewAnalysisType analysisType, ReviewAnalysisStatus status, LocalDateTime now, Limit limit);

	Optional<ReviewAnalysisRequest> findByReviewIdAndAnalysisType(Long reviewId, ReviewAnalysisType analysisType);
}
