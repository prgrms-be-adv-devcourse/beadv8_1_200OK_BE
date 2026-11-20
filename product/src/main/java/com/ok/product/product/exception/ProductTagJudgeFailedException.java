package com.ok.product.product.exception;

import lombok.Getter;

/**
 * 태그 판정기가 결과를 얻지 못했을 때 던진다.
 * 다시 보내면 성공할 수 있는 실패(429·5xx·시간 초과 등)와 다시 보내도 같은 답이 올 최종 실패를 구분한다.
 */
@Getter
public class ProductTagJudgeFailedException extends RuntimeException {

	private final boolean retryable;

	private ProductTagJudgeFailedException(String message, Throwable cause, boolean retryable) {
		super(message, cause);
		this.retryable = retryable;
	}

	public static ProductTagJudgeFailedException retryable(String message, Throwable cause) {
		return new ProductTagJudgeFailedException(message, cause, true);
	}

	public static ProductTagJudgeFailedException finalFailure(String message, Throwable cause) {
		return new ProductTagJudgeFailedException(message, cause, false);
	}
}
