package com.ok.product.review.domain;

/**
 * 리뷰 키워드가 속한 분석 항목이다. 값은 키워드 목록({@code review-analysis/keywords.json})의 {@code aspect}를 대문자로 쓴 것이다.
 * 감성의 {@link ReviewAspect}와 따로 둔다. 감성 분류 기준 검사가 {@link ReviewAspect}의 모든 값을 질문과 맞춰 보므로, 기타(OTHER)를 더하면 감성 쪽 기동이 깨진다.
 */
public enum ReviewKeywordAspect {
	TASTE,
	FRESHNESS,
	CONDITION,
	PACKAGING,
	DELIVERY,
	PRICE,
	// 여섯 분석 항목에 없는 장점(선물용, 간편함 등)
	OTHER
}
