package com.ok.product.review.client.jev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Component;

import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewSentimentAnalyzer;
import com.ok.product.review.client.ReviewSentimentCriteria;
import com.ok.product.review.client.ReviewSentimentCriteria.Choice;
import com.ok.product.review.client.ReviewSentimentCriteria.Question;
import com.ok.product.review.domain.AspectSentiment;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.Sentiment;
import com.ok.product.review.service.dto.ReviewSentimentAnalysis;
import com.ok.product.review.service.dto.ReviewSentimentInput;

import lombok.RequiredArgsConstructor;

/**
 * Jev로 리뷰 감성을 분석한다. 리뷰 1건을 질문 7개(전체 감성과 분석 항목 6개)를 담은 요청 1회로 보낸다.
 * 요청 모양은 #385 실험과 같다. 상품명·옵션명·본문만 보내고 회원·주문·리뷰 식별자는 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JevReviewSentimentAnalyzer implements ReviewSentimentAnalyzer {

	private static final String CHOICE_TYPE = "choice";

	private final JevClient jevClient;
	private final ReviewSentimentCriteria criteria;
	private final JevProperties properties;

	@Override
	public ReviewSentimentAnalysis analyze(ReviewSentimentInput input) {
		JevResponse response = jevClient.send(toRequest(input));
		Map<String, String> choices = choices(response);

		Map<ReviewAspect, AspectSentiment> aspects = new EnumMap<>(ReviewAspect.class);
		for (ReviewAspect aspect : ReviewAspect.values()) {
			aspects.put(aspect, AspectSentiment.valueOf(toEnumName(choices.get(aspect.name().toLowerCase(Locale.ROOT)))));
		}
		Sentiment overall = Sentiment.valueOf(toEnumName(choices.get(ReviewSentimentCriteria.OVERALL_KEY)));
		return new ReviewSentimentAnalysis(overall, aspects, criteria.version(), properties.model());
	}

	private JevRequest toRequest(ReviewSentimentInput input) {
		// Jev는 앞쪽 선택지를 더 고르는 경향이 있어 순서를 섞는다. 시드가 같으면 늘 같은 순서를 받는다
		Random random = new Random(input.choiceSeed());
		Map<String, JevRequest.Question> questions = new LinkedHashMap<>();
		for (Question question : criteria.questions()) {
			List<Choice> shuffled = new ArrayList<>(question.choices());
			Collections.shuffle(shuffled, random);
			Map<String, String> choiceCriteria = new LinkedHashMap<>();
			for (Choice choice : shuffled) {
				choiceCriteria.put(choice.value(), choice.label() + ": " + choice.description());
			}
			// Jev에는 공통 지시문 자리가 없어 질문마다 앞에 붙인다
			String instructions = criteria.instruction() + "\n" + question.question() + "\n" + question.guide();
			questions.put(question.key(), new JevRequest.Question(CHOICE_TYPE, instructions, choiceCriteria));
		}
		return new JevRequest(properties.model(), state(input), questions);
	}

	// 옵션명은 #385 실험 조건에 없어 검증하지 않은 조건이다. 옵션이 없는 상품은 실험과 같은 모양으로 보낸다.
	// 상품명은 운영 DB에서 외래키 검사를 끄고 상품 행을 지우면 없을 수 있다. "null"이라는 상품명을 보내지 않게 줄을 뺀다
	private static String state(ReviewSentimentInput input) {
		StringBuilder state = new StringBuilder();
		if (hasText(input.productName())) {
			state.append("상품: ").append(input.productName()).append('\n');
		}
		if (hasText(input.optionName())) {
			state.append("옵션: ").append(input.optionName()).append('\n');
		}
		return state.append("본문: ").append(input.content()).toString();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	// 질문마다 선택 값을 꺼낸다. 다시 보내도 같은 응답이 올 가능성이 높아 모두 최종 실패로 본다
	private Map<String, String> choices(JevResponse response) {
		if (response == null || response.answers() == null) {
			throw ReviewAnalysisFailedException.finalFailure("Jev 응답에 answers가 없습니다.", null);
		}
		Map<String, String> choices = new LinkedHashMap<>();
		for (Question question : criteria.questions()) {
			JevResponse.Answer answer = response.answers().get(question.key());
			if (answer == null) {
				throw ReviewAnalysisFailedException.finalFailure("Jev 응답에 질문의 답이 없습니다. key=" + question.key(), null);
			}
			if (!CHOICE_TYPE.equals(answer.type())) {
				throw ReviewAnalysisFailedException.finalFailure(
					"Jev 응답이 선택 답이 아닙니다. key=" + question.key() + ", type=" + answer.type(), null);
			}
			boolean allowed = question.choices().stream().anyMatch(choice -> choice.value().equals(answer.choice()));
			if (!allowed) {
				throw ReviewAnalysisFailedException.finalFailure(
					"Jev 응답이 선택지에 없는 값입니다. key=" + question.key() + ", choice=" + answer.choice(), null);
			}
			choices.put(question.key(), answer.choice());
		}
		return choices;
	}

	private static String toEnumName(String choice) {
		return choice.toUpperCase(Locale.ROOT);
	}
}
