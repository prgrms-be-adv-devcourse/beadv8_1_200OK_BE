package com.ok.product.product.domain;

/**
 * 상품 태그 판정 상태다.
 */
public enum ProductTagJudgmentStatus {
	PENDING,
	COMPLETED,
	// 다시 시도하지 않는 실패. 기동 시 백필이 다시 대기로 넣는다
	FAILED
}
