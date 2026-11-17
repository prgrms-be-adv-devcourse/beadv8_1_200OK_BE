package com.ok.product.review.application.port.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;

import com.ok.product.review.domain.ReviewAnalysisRequest;
import com.ok.product.review.domain.ReviewAnalysisStatus;
import com.ok.product.review.domain.ReviewAnalysisType;

/**
 * 리뷰 분석 요청 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ReviewAnalysisRequestRepository {

	/**
	 * 작업자가 꺼낼 요청을 오래 기다린 순서로 잠가 조회한다.
	 * 다른 작업자가 꺼내는 중인 행은 기다리지 않고 건너뛴다. 잠금은 커밋 뒤에 풀리므로 꺼낸 요청은 claim으로 미뤄야 다시 꺼내지지 않는다.
	 */
	List<ReviewAnalysisRequest> findByAnalysisTypeAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
		ReviewAnalysisType analysisType, ReviewAnalysisStatus status, LocalDateTime now, Limit limit);

	Optional<ReviewAnalysisRequest> findByReviewIdAndAnalysisType(Long reviewId, ReviewAnalysisType analysisType);

	Optional<ReviewAnalysisRequest> findById(Long id);

	<S extends ReviewAnalysisRequest> S save(S request);

}
