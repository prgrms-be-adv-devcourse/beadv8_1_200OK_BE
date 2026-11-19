package com.ok.product.product.adapter.out.jev;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 태그 판정은 선택지별 확률만 쓴다. 선택 값·신뢰도·사용량은 쓰지 않는다
@JsonIgnoreProperties(ignoreUnknown = true)
record JevResponse(Map<String, Answer> answers) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Answer(Map<String, Double> probabilities) {
	}
}
