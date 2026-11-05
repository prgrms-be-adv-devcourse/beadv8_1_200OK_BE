package com.ok.product.review.domain;

/**
 * 리뷰에서 따로 감성을 읽는 분석 항목이다.
 * 값과 순서는 분류 기준({@code review-analysis/criteria.json})의 항목 질문과 같고, 감성 집계도 이 순서로 보여 준다.
 */
public enum ReviewAspect {
	TASTE,
	FRESHNESS,
	CONDITION,
	PACKAGING,
	DELIVERY,
	PRICE
}
