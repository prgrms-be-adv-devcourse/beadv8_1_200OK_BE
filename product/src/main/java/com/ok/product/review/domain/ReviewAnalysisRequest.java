package com.ok.product.review.domain;

import java.time.LocalDateTime;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.product.ProductSchema;
import com.ok.product.review.exception.ProductReviewErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 리뷰 하나를 분석 종류 하나로 분석해 달라는 요청이다. (리뷰, 분석 종류)마다 하나만 있다.
 * 대기에서 완료, 최종 실패, 분석 안 함(내용 없는 리뷰), 취소(삭제된 리뷰) 중 하나로 끝나고, 작업자는 다음 시도 시각이 지난 대기 요청만 꺼낸다.
 * 리뷰를 수정하면 어느 상태에서든 처음 대기로 되돌린다.
 */
@Entity
@Table(name = "review_analysis_request", catalog = ProductSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewAnalysisRequest extends BaseTime {

	private static final int LAST_ERROR_MAX_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "review_id", nullable = false, updatable = false)
	private Long reviewId;

	@Enumerated(EnumType.STRING)
	@Column(name = "analysis_type", nullable = false, length = 20, updatable = false)
	private ReviewAnalysisType analysisType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReviewAnalysisStatus status;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at", nullable = false)
	private LocalDateTime nextAttemptAt;

	@Column(name = "last_error", length = LAST_ERROR_MAX_LENGTH)
	private String lastError;

	public static ReviewAnalysisRequest pending(Long reviewId, ReviewAnalysisType analysisType, LocalDateTime now) {
		ReviewAnalysisRequest request = new ReviewAnalysisRequest();
		request.reviewId = reviewId;
		request.analysisType = analysisType;
		request.status = ReviewAnalysisStatus.PENDING;
		request.nextAttemptAt = now;
		return request;
	}

	public void complete() {
		validatePending();
		status = ReviewAnalysisStatus.COMPLETED;
	}

	public void skip() {
		validatePending();
		status = ReviewAnalysisStatus.SKIPPED;
	}

	public void cancel() {
		validatePending();
		status = ReviewAnalysisStatus.CANCELLED;
	}

	/**
	 * 수정된 리뷰를 새로 분석하도록 처음 대기로 되돌린다.
	 * 이전 시도는 수정 전 내용에 대한 것이라, 백오프 중이거나 최종 실패한 요청도 시도 횟수와 마지막 오류를 지우고 바로 꺼내지게 한다.
	 */
	public void reset(LocalDateTime now) {
		status = ReviewAnalysisStatus.PENDING;
		attemptCount = 0;
		nextAttemptAt = now;
		lastError = null;
	}

	/**
	 * 다시 시도하지 않고 최종 실패로 끝낸다. 운영자가 원인을 볼 수 있게 마지막 오류를 남긴다.
	 */
	public void fail(String error) {
		validatePending();
		status = ReviewAnalysisStatus.FAILED;
		attemptCount++;
		lastError = truncate(error);
	}

	private void validatePending() {
		if (status != ReviewAnalysisStatus.PENDING) {
			throw new RestApiException(ProductReviewErrorCode.ANALYSIS_REQUEST_NOT_PENDING);
		}
	}

	private static String truncate(String error) {
		if (error == null || error.length() <= LAST_ERROR_MAX_LENGTH) {
			return error;
		}
		return error.substring(0, LAST_ERROR_MAX_LENGTH);
	}
}
