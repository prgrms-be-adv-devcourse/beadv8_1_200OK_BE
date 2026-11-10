package com.ok.product.review.service.dto;

/**
 * 노출할 키워드 하나와 그 키워드를 표현한 리뷰 수다. 문구와 분석 항목은 현재 키워드 목록에서 붙인다.
 *
 * @param aspect 분석 항목 이름({@code TASTE} 등)
 */
public record ReviewKeywordCountResult(
	String keywordId,
	String phrase,
	String aspect,
	long reviewCount
) {
}
