package com.ok.product.review.client.jev;

import java.util.Map;

// Jev는 questions와 criteria의 키 삽입 순서를 그대로 읽으므로 두 맵은 순서를 지키는 구현을 넣는다
record JevRequest(String model, String state, Map<String, Question> questions) {

	record Question(String type, String instructions, Map<String, String> criteria) {
	}
}
