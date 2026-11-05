package com.ok.product.review.client.jev;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 감성 판정에 필요한 선택 값만 받는다. 신뢰도·확률·사용량은 쓰지 않는다
@JsonIgnoreProperties(ignoreUnknown = true)
record JevResponse(Map<String, Answer> answers) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Answer(String type, String choice) {
	}
}
