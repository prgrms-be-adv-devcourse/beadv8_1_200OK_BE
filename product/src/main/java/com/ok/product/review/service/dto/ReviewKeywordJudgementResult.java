package com.ok.product.review.service.dto;

/**
 * 물은 키워드 하나의 "말함" 확률과 판정이다.
 *
 * @param aspect                {@code ReviewKeywordAspect} 이름
 * @param expressed             리뷰가 이 키워드를 표현했다고 판정했는지. 운영 작업자가 저장하는 표현한 키워드와 같다
 * @param excludedByTextureRule 확률은 기준을 넘었지만 구체적인 식감 키워드가 있어 표현하지 않은 것으로 본 "식감이 좋아요"인지
 */
public record ReviewKeywordJudgementResult(
	String keywordId,
	String phrase,
	String aspect,
	double probability,
	boolean expressed,
	boolean excludedByTextureRule
) {
}
