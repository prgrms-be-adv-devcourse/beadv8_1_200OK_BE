package com.ok.product.review.domain;

/**
 * 전체 감성과, 언급된 분석 항목의 감성이다.
 * 값은 분류 기준({@code review-analysis/criteria.json})의 전체 감성 선택지와 같다.
 */
public enum Sentiment {
	POSITIVE,
	NEGATIVE,
	NEUTRAL,
	MIXED
}
