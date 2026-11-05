package com.ok.product.review.domain;

public enum ReviewAnalysisStatus {
	PENDING,
	COMPLETED,
	FAILED,
	// 내용 없는 리뷰라 분석기를 부르지 않았다
	SKIPPED,
	// 리뷰가 삭제되어 분석하지 않는다
	CANCELLED
}
