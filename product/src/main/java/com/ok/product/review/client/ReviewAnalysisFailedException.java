package com.ok.product.review.client;

import lombok.Getter;

/**
 * 리뷰 분석기가 결과를 얻지 못했을 때 던진다.
 * 다시 보내면 성공할 수 있는 실패(429·5xx·시간 초과 등)와 다시 보내도 같은 답이 올 최종 실패를 구분한다.
 */
@Getter
public class ReviewAnalysisFailedException extends RuntimeException {

	private final boolean retryable;

	private ReviewAnalysisFailedException(String message, Throwable cause, boolean retryable) {
		super(message, cause);
		this.retryable = retryable;
	}

	public static ReviewAnalysisFailedException retryable(String message, Throwable cause) {
		return new ReviewAnalysisFailedException(message, cause, true);
	}

	public static ReviewAnalysisFailedException finalFailure(String message, Throwable cause) {
		return new ReviewAnalysisFailedException(message, cause, false);
	}
}
