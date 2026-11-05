package com.ok.product.review.client;

/**
 * 리뷰 분석기가 어떤 요청도 처리할 수 없을 때(인증 거절 등) 던진다.
 * 실패 원인이 이 리뷰가 아니라 분석기 쪽 설정에 있으므로, 요청의 실패로 기록하지 않는다.
 */
public class ReviewAnalyzerUnavailableException extends RuntimeException {

	public ReviewAnalyzerUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
