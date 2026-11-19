package com.ok.product.product.adapter.out.jev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Component;

import com.ok.product.config.JevProperties;
import com.ok.product.product.application.dto.ProductTagInput;
import com.ok.product.product.application.dto.ProductTagScores;
import com.ok.product.product.application.port.out.ProductTagJudge;
import com.ok.product.product.application.service.ProductTagList;
import com.ok.product.product.application.service.ProductTagList.Choice;
import com.ok.product.product.application.service.ProductTagList.Tag;
import com.ok.product.product.exception.ProductTagJudgeFailedException;

import lombok.RequiredArgsConstructor;

/**
 * Jev로 상품에 맞는 상황·용도 태그를 판정한다. 상품 1개를 태그 목록 전체(18개)를 질문으로 담은 요청 1회로 보낸다.
 * 요청 모양은 평가 스크립트(jev_tag_v3.py의 request)와 같다. 상품명과 옵션명만 보내고 판매자·가격·설명은 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JevProductTagJudge implements ProductTagJudge {

	private static final String CHOICE_TYPE = "choice";
	private static final String APPLIES = "applies";

	private final JevTagClient jevTagClient;
	private final ProductTagList tagList;
	private final JevProperties properties;

	@Override
	public ProductTagScores judge(ProductTagInput input) {
		List<Tag> tags = tagList.tags();
		JevResponse response = jevTagClient.send(toRequest(input, tags));
		return new ProductTagScores(probabilities(response, tags), tagList.version(), properties.model());
	}

	private JevRequest toRequest(ProductTagInput input, List<Tag> tags) {
		// 선택지가 둘이라 Jev의 앞쪽 선택지 치우침이 곧 오탐이 되어 순서를 섞는다. 상품 ID가 시드라 같은 상품은 늘 같은 순서를 받는다.
		// 평가 스크립트는 파이썬 난수라 같은 시드여도 순서가 다르다. 평가가 잰 것은 상품마다 섞인다는 성질이라 리뷰 분석처럼 자바 난수를 쓴다
		Random random = new Random(input.choiceSeed());
		Map<String, JevRequest.Question> questions = new LinkedHashMap<>();
		for (int i = 0; i < tags.size(); i++) {
			Tag tag = tags.get(i);
			List<Choice> shuffled = new ArrayList<>(tagList.choices());
			Collections.shuffle(shuffled, random);
			// 선택지 설명이 이미 "해당: "으로 시작해 이름을 따로 붙이지 않는다
			Map<String, String> criteria = new LinkedHashMap<>();
			for (Choice choice : shuffled) {
				criteria.put(choice.value(), choice.description());
			}
			// Jev에는 공통 지시문 자리가 없어 질문마다 앞에 붙인다
			String instructions = tagList.instruction() + "\n이 상품에 \"" + tag.label() + "\" 태그를 붙이나?\n판정 기준: "
				+ tag.description();
			questions.put(questionKey(i), new JevRequest.Question(CHOICE_TYPE, instructions, criteria));
		}
		return new JevRequest(properties.model(), state(input), questions);
	}

	// 질문 키는 평가 스크립트처럼 목록 순번(t0, t1, ...)이다. 키도 모델이 읽는 입력이라 평가와 다르게 두지 않는다
	private static String questionKey(int index) {
		return "t" + index;
	}

	private static String state(ProductTagInput input) {
		return "상품: " + input.productName() + "\n옵션: " + String.join(", ", input.optionNames());
	}

	// 물은 태그마다 "해당" 확률을 꺼낸다. 다시 보내도 같은 응답이 올 가능성이 높아 모두 최종 실패로 본다
	private static Map<String, Double> probabilities(JevResponse response, List<Tag> tags) {
		if (response == null || response.answers() == null) {
			throw ProductTagJudgeFailedException.finalFailure("Jev 응답에 answers가 없습니다.", null);
		}
		Map<String, Double> probabilities = new LinkedHashMap<>();
		for (int i = 0; i < tags.size(); i++) {
			String key = questionKey(i);
			JevResponse.Answer answer = response.answers().get(key);
			if (answer == null) {
				throw ProductTagJudgeFailedException.finalFailure("Jev 응답에 태그의 답이 없습니다. key=" + key, null);
			}
			Double applies = answer.probabilities() == null ? null : answer.probabilities().get(APPLIES);
			if (applies == null) {
				throw ProductTagJudgeFailedException.finalFailure("Jev 응답에 \"해당\" 확률이 없습니다. key=" + key, null);
			}
			if (applies < 0 || applies > 1) {
				throw ProductTagJudgeFailedException.finalFailure(
					"Jev 응답의 \"해당\" 확률이 0~1 밖입니다. key=" + key + ", probability=" + applies, null);
			}
			probabilities.put(tags.get(i).id(), applies);
		}
		return probabilities;
	}
}
