package com.ok.product.review.service.dto;

import java.util.Map;

import com.ok.product.review.domain.Sentiment;

// 감성 네 범주의 리뷰 수. 없는 범주는 0이다
public record SentimentCountsResult(
	long positive,
	long negative,
	long neutral,
	long mixed
) {

	public static SentimentCountsResult from(Map<Sentiment, Long> counts) {
		return new SentimentCountsResult(
			counts.getOrDefault(Sentiment.POSITIVE, 0L),
			counts.getOrDefault(Sentiment.NEGATIVE, 0L),
			counts.getOrDefault(Sentiment.NEUTRAL, 0L),
			counts.getOrDefault(Sentiment.MIXED, 0L));
	}

	public long total() {
		return positive + negative + neutral + mixed;
	}
}
