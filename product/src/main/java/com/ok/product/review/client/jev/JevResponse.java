package com.ok.product.review.client.jev;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 감성은 선택 값을, 키워드는 선택지별 확률을 쓴다. 신뢰도·사용량은 쓰지 않는다
@JsonIgnoreProperties(ignoreUnknown = true)
record JevResponse(Map<String, Answer> answers) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Answer(String type, String choice, Map<String, Double> probabilities) {
	}
}
