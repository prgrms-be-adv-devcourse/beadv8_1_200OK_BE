package com.ok.product.review.domain;

// 분석 요청은 (리뷰, 분석 종류)마다 하나다. 리뷰를 쓰거나 고치면 모든 종류의 요청을 함께 만들거나 되돌린다
public enum ReviewAnalysisType {
	SENTIMENT,
	KEYWORD
}
