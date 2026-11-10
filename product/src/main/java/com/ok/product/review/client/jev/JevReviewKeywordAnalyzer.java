package com.ok.product.review.client.jev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Component;

import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewKeywordAnalyzer;
import com.ok.product.review.client.ReviewKeywordList;
import com.ok.product.review.client.ReviewKeywordList.Choice;
import com.ok.product.review.client.ReviewKeywordList.Keyword;
import com.ok.product.review.service.dto.ReviewKeywordAnalysis;
import com.ok.product.review.service.dto.ReviewKeywordInput;

import lombok.RequiredArgsConstructor;

/**
 * Jev로 리뷰가 키워드를 말했는지 분석한다. 리뷰 1건을 상품 하위 분류의 키워드(12~30개)를 질문으로 담은 요청 1회로 보낸다.
 * 요청 모양은 #385 실험과 같다. 상품명과 본문만 보내고 옵션명과 회원·주문·리뷰 식별자는 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JevReviewKeywordAnalyzer implements ReviewKeywordAnalyzer {

	private static final String CHOICE_TYPE = "choice";
	private static final String MENTIONED = "mentioned";

	private final JevClient jevClient;
	private final ReviewKeywordList keywordList;
	private final JevProperties properties;

	@Override
	public ReviewKeywordAnalysis analyze(ReviewKeywordInput input) {
		List<Keyword> keywords = keywordList.keywordsFor(input.category());
		JevResponse response = jevClient.send(toRequest(input, keywords));
		return new ReviewKeywordAnalysis(probabilities(response, keywords), keywordList.version(), properties.model());
	}

	private JevRequest toRequest(ReviewKeywordInput input, List<Keyword> keywords) {
		// 선택지가 둘이라 Jev의 앞쪽 선택지 치우침이 곧 오탐이 되어 순서를 섞는다. 시드가 같으면 늘 같은 순서를 받는다
		Random random = new Random(input.choiceSeed());
		Map<String, JevRequest.Question> questions = new LinkedHashMap<>();
		for (Keyword keyword : keywords) {
			List<Choice> shuffled = new ArrayList<>(keywordList.choices());
			Collections.shuffle(shuffled, random);
			// 선택지 설명이 이미 "말함: "으로 시작해 이름을 따로 붙이지 않는다
			Map<String, String> criteria = new LinkedHashMap<>();
			for (Choice choice : shuffled) {
				criteria.put(choice.value(), choice.description());
			}
			// Jev에는 공통 지시문 자리가 없어 질문마다 앞에 붙인다
			String instructions = keywordList.instruction() + "\n이 리뷰는 \"" + keyword.phrase() + "\"라고 말하나?\n판정 기준: "
				+ keyword.guide();
			questions.put(keyword.id(), new JevRequest.Question(CHOICE_TYPE, instructions, criteria));
		}
		return new JevRequest(properties.model(), state(input), questions);
	}

	// 상품명은 운영 DB에서 외래키 검사를 끄고 상품 행을 지우면 없을 수 있다. "null"이라는 상품명을 보내지 않게 줄을 뺀다
	private static String state(ReviewKeywordInput input) {
		StringBuilder state = new StringBuilder();
		if (input.productName() != null && !input.productName().isBlank()) {
			state.append("상품: ").append(input.productName()).append('\n');
		}
		return state.append("본문: ").append(input.content()).toString();
	}

	// 물은 키워드마다 "말함" 확률을 꺼낸다. 다시 보내도 같은 응답이 올 가능성이 높아 모두 최종 실패로 본다
	private static Map<String, Double> probabilities(JevResponse response, List<Keyword> keywords) {
		if (response == null || response.answers() == null) {
			throw ReviewAnalysisFailedException.finalFailure("Jev 응답에 answers가 없습니다.", null);
		}
		Map<String, Double> probabilities = new LinkedHashMap<>();
		for (Keyword keyword : keywords) {
			JevResponse.Answer answer = response.answers().get(keyword.id());
			if (answer == null) {
				throw ReviewAnalysisFailedException.finalFailure("Jev 응답에 키워드의 답이 없습니다. key=" + keyword.id(), null);
			}
			Double mentioned = answer.probabilities() == null ? null : answer.probabilities().get(MENTIONED);
			if (mentioned == null) {
				throw ReviewAnalysisFailedException.finalFailure(
					"Jev 응답에 \"말함\" 확률이 없습니다. key=" + keyword.id(), null);
			}
			if (mentioned < 0 || mentioned > 1) {
				throw ReviewAnalysisFailedException.finalFailure(
					"Jev 응답의 \"말함\" 확률이 0~1 밖입니다. key=" + keyword.id() + ", probability=" + mentioned, null);
			}
			probabilities.put(keyword.id(), mentioned);
		}
		return probabilities;
	}
}
