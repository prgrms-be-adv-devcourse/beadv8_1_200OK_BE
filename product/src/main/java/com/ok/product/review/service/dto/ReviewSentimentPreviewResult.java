package com.ok.product.review.service.dto;

import java.util.Map;

/**
 * 본문 하나의 감성 분석 테스트 결과다. 내용 없는 본문이면 분석하지 않아 감성·버전·모델이 없고 분석 항목이 비어 있다.
 *
 * @param overall 전체 감성({@code Sentiment}) 이름
 * @param aspects 분석 항목({@code ReviewAspect}) 이름마다 항목 감성({@code AspectSentiment}) 이름. 여섯 항목 모두 있다
 */
public record ReviewSentimentPreviewResult(
	boolean analyzed,
	String criteriaVersion,
	String model,
	String overall,
	Map<String, String> aspects
) {

	public static ReviewSentimentPreviewResult notAnalyzed() {
		return new ReviewSentimentPreviewResult(false, null, null, null, Map.of());
	}
}
