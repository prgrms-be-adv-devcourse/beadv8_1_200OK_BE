package com.ok.product.review.service.dto;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import com.ok.product.review.domain.AspectSentiment;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.Sentiment;

/**
 * 감성 분석기가 돌려준 리뷰 하나의 분석 결과다.
 * 여섯 분석 항목이 언급 없음까지 모두 있어야 한다. 빠진 항목을 언급 없음으로 저장하면 집계가 조용히 틀어지기 때문이다.
 *
 * @param criteriaVersion 분석에 쓴 분류 기준(criteria.json)의 {@code version}
 */
public record ReviewSentimentAnalysis(
	Sentiment overall,
	Map<ReviewAspect, AspectSentiment> aspects,
	String criteriaVersion,
	String model
) {

	public ReviewSentimentAnalysis {
		if (overall == null || criteriaVersion == null || model == null) {
			throw new IllegalArgumentException("전체 감성, 분류 기준 버전, 모델은 필수입니다.");
		}
		if (aspects == null || !aspects.keySet().equals(EnumSet.allOf(ReviewAspect.class)) || aspects.containsValue(null)) {
			throw new IllegalArgumentException("여섯 분석 항목의 감성이 모두 있어야 합니다. aspects=" + aspects);
		}
		aspects = Collections.unmodifiableMap(new EnumMap<>(aspects));
	}
}
