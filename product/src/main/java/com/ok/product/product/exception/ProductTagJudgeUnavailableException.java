package com.ok.product.product.exception;

/**
 * 태그 판정기가 어떤 요청도 처리할 수 없을 때(인증 거절 등) 던진다.
 * 실패 원인이 이 상품이 아니라 판정기 쪽 설정에 있으므로, 판정 요청의 실패로 기록하지 않는다.
 */
public class ProductTagJudgeUnavailableException extends RuntimeException {

	public ProductTagJudgeUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
