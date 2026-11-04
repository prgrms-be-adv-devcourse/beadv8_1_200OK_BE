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

	private static final String LOCATION = "review-analysis/criteria.json";

	private static final String OVERALL_KEY = "overall";

	private final String version;

	public ReviewSentimentCriteria() {
		JsonNode root = read();
		validate(root);
		this.version = root.path("version").asString();
	}

	public String version() {
		return version;
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

	private static List<String> values(JsonNode choices) {
		return choices.values().stream().map(choice -> choice.path("value").asString()).toList();
	}

	private static List<String> keys(Enum<?>[] constants) {
		return Arrays.stream(constants).map(constant -> constant.name().toLowerCase(Locale.ROOT)).toList();
	}
}
