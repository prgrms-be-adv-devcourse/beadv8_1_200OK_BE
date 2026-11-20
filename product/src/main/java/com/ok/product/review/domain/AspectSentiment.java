package com.ok.product.review.domain;

import java.util.Optional;

/**
 * 분석기가 분석 항목마다 돌려주는 감성이다. 값은 분류 기준({@code review-analysis/criteria.json})의 {@code aspectChoices}와 같다.
 * 분석기가 항목을 빠뜨린 것과 리뷰가 언급하지 않은 것을 구분하려고 언급 없음을 값으로 둔다.
 */
public enum AspectSentiment {
	POSITIVE(Sentiment.POSITIVE),
	NEGATIVE(Sentiment.NEGATIVE),
	NEUTRAL(Sentiment.NEUTRAL),
	MIXED(Sentiment.MIXED),
	NOT_MENTIONED(null);

	private final Sentiment sentiment;

	AspectSentiment(Sentiment sentiment) {
		this.sentiment = sentiment;
	}

	// 언급 없음이면 비어 있다. 언급 없음은 저장하지 않고 항목 분모에도 넣지 않는다
	public Optional<Sentiment> mentioned() {
		return Optional.ofNullable(sentiment);
	}
}
