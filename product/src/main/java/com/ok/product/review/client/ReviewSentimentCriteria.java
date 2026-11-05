package com.ok.product.review.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.ok.product.review.domain.AspectSentiment;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.Sentiment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리뷰 감성 분류 기준({@code review-analysis/criteria.json})을 읽는다. 모델 비교 실험(#385)과 서비스가 같은 파일 하나를 쓴다.
 * 파일의 질문·선택지가 {@link ReviewAspect}, {@link Sentiment}, {@link AspectSentiment}와 다르면 앱이 뜨지 않는다.
 * 기준만 바꾸고 코드를 그대로 두면 분석 결과를 저장하지 못하거나 엉뚱한 항목에 저장하기 때문이다.
 */
// TODO(#422): 분류 기준 버전이 바뀌어도 이미 분석한 리뷰를 일괄 재분석하지 않는다. 결과의 criteria_version으로 대상을 고를 수 있다
@Component
public class ReviewSentimentCriteria {

	public static final String OVERALL_KEY = "overall";

	private static final String LOCATION = "review-analysis/criteria.json";

	private final String version;
	private final String instruction;
	private final List<Question> questions;

	public ReviewSentimentCriteria() {
		JsonNode root = read();
		validate(root);
		this.version = root.path("version").asString();
		this.instruction = root.path("instruction").asString();
		List<Choice> aspectChoices = choices(root.path("aspectChoices"));
		this.questions = root.path("questions").values().stream()
			.map(question -> toQuestion(question, aspectChoices))
			.toList();
	}

	public String version() {
		return version;
	}

	// 모든 질문 앞에 붙이는 공통 지시문이다
	public String instruction() {
		return instruction;
	}

	// 전체 감성이 맨 앞이고, 이어서 분석 항목이 ReviewAspect 순서로 온다
	public List<Question> questions() {
		return questions;
	}

	/**
	 * 분석기에 묻는 질문 하나다. 선택지는 파일에 적힌 순서다.
	 *
	 * @param key 전체 감성은 {@link #OVERALL_KEY}, 분석 항목은 {@link ReviewAspect} 이름의 소문자
	 */
	public record Question(String key, String question, String guide, List<Choice> choices) {
	}

	/**
	 * 질문의 선택지 하나다.
	 *
	 * @param value 분석기가 답으로 고르는 값. 감성 enum 이름의 소문자다
	 */
	public record Choice(String value, String label, String description) {
	}

	private static JsonNode read() {
		try (InputStream input = new ClassPathResource(LOCATION).getInputStream()) {
			return JsonMapper.shared().readTree(input);
		} catch (IOException e) {
			throw new UncheckedIOException("분류 기준 파일을 읽지 못했습니다. location=" + LOCATION, e);
		}
	}

	private static void validate(JsonNode root) {
		List<JsonNode> questions = List.copyOf(root.path("questions").values());
		List<String> questionKeys = questions.stream().map(question -> question.path("key").asString()).toList();
		List<String> expectedKeys = Stream.concat(Stream.of(OVERALL_KEY), keys(ReviewAspect.values()).stream()).toList();
		check("질문", questionKeys, expectedKeys);
		check("전체 감성 선택지", values(questions.getFirst().path("choices")), keys(Sentiment.values()));
		check("항목 감성 선택지", values(root.path("aspectChoices")), keys(AspectSentiment.values()));
		if (root.path("version").asString().isBlank()) {
			throw new IllegalStateException("분류 기준에 version이 없습니다. location=" + LOCATION);
		}
	}

	private static void check(String target, List<String> actual, List<String> expected) {
		if (!actual.equals(expected)) {
			throw new IllegalStateException(
				"분류 기준과 코드의 " + target + " 목록이 다릅니다. criteria=" + actual + ", code=" + expected);
		}
	}

	// 분석 항목 질문은 선택지를 따로 적지 않고 공통 aspectChoices를 쓴다
	private static Question toQuestion(JsonNode question, List<Choice> aspectChoices) {
		String key = question.path("key").asString();
		List<Choice> choices = OVERALL_KEY.equals(key) ? choices(question.path("choices")) : aspectChoices;
		return new Question(key, question.path("question").asString(), question.path("guide").asString(), choices);
	}

	private static List<Choice> choices(JsonNode choices) {
		return choices.values().stream()
			.map(choice -> new Choice(
				choice.path("value").asString(), choice.path("label").asString(), choice.path("description").asString()))
			.toList();
	}

	private static List<String> values(JsonNode choices) {
		return choices.values().stream().map(choice -> choice.path("value").asString()).toList();
	}

	private static List<String> keys(Enum<?>[] constants) {
		return Arrays.stream(constants).map(constant -> constant.name().toLowerCase(Locale.ROOT)).toList();
	}
}
