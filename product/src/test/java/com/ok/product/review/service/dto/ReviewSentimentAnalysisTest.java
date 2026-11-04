package com.ok.product.review.service.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ok.product.review.domain.AspectSentiment;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.Sentiment;

class ReviewSentimentAnalysisTest {

	@Test
	void 분석_항목이_하나라도_빠지면_결과로_받지_않는다() {
		// given: 가격이 빠졌다. 언급 없음으로 채우면 분석기 오류가 집계에 섞인다
		Map<ReviewAspect, AspectSentiment> aspects = new EnumMap<>(ReviewAspect.class);
		for (ReviewAspect aspect : ReviewAspect.values()) {
			aspects.put(aspect, AspectSentiment.POSITIVE);
		}
		aspects.remove(ReviewAspect.PRICE);

		// when & then
		assertThatThrownBy(() -> new ReviewSentimentAnalysis(Sentiment.POSITIVE, aspects, "v0.2", "jev-1.14.0"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageStartingWith("여섯 분석 항목의 감성이 모두 있어야 합니다.");
	}
}
