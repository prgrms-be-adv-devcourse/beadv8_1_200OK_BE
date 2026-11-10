package com.ok.product.review.client.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewAnalyzerUnavailableException;
import com.ok.product.review.client.ReviewSentimentCriteria;
import com.ok.product.review.domain.AspectSentiment;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.Sentiment;
import com.ok.product.review.service.dto.ReviewSentimentAnalysis;
import com.ok.product.review.service.dto.ReviewSentimentInput;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 실제 Jev를 부르지 않고 가짜 HTTP 서버로 요청 본문과 응답 해석, 실패 구분을 확인한다
class JevReviewSentimentAnalyzerTest {

	private static final String BASE_URL = "https://api.jev.test";
	private static final String ANALYZE_URL = BASE_URL + "/v1/systemone";
	private static final String API_KEY = "test-jev-key";
	private static final String MODEL = "jev-1.14.0";

	private static final List<String> QUESTION_KEYS =
		List.of("overall", "taste", "freshness", "condition", "packaging", "delivery", "price");
	private static final List<String> OVERALL_CHOICES = List.of("positive", "negative", "neutral", "mixed");
	private static final List<String> ASPECT_CHOICES = List.of("positive", "negative", "neutral", "mixed", "not_mentioned");

	private final List<String> sentBodies = new ArrayList<>();
	private MockRestServiceServer server;
	private JevReviewSentimentAnalyzer analyzer;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		JevProperties properties = new JevProperties(BASE_URL, API_KEY, MODEL, Duration.ofSeconds(3), Duration.ofSeconds(10));
		analyzer = new JevReviewSentimentAnalyzer(new JevClient(builder.build(), properties), new ReviewSentimentCriteria(), properties);
	}

	// 요청

	@Test
	void 상품명_옵션명_본문을_보내고_리뷰_ID는_보내지_않는다() {
		// given
		expectSuccess();

		// when
		analyzer.analyze(new ReviewSentimentInput(987654L, "청송 꿀사과", "5kg 한 상자", "아삭하고 달아요"));

		// then
		String body = sentBodies.getFirst();
		assertSoftly(softly -> {
			softly.assertThat(json(body).path("state").asString()).isEqualTo("상품: 청송 꿀사과\n옵션: 5kg 한 상자\n본문: 아삭하고 달아요");
			softly.assertThat(body).doesNotContain("987654");
		});
	}

	@ParameterizedTest
	@MethodSource("빈_옵션명")
	void 옵션명이_없으면_옵션_줄_없이_보낸다(String optionName) {
		// given
		expectSuccess();

		// when
		analyzer.analyze(new ReviewSentimentInput(1L, "청송 꿀사과", optionName, "아삭하고 달아요"));

		// then
		assertThat(json(sentBodies.getFirst()).path("state").asString()).isEqualTo("상품: 청송 꿀사과\n본문: 아삭하고 달아요");
	}

	static Stream<String> 빈_옵션명() {
		return Stream.of(null, "", " ");
	}

	@Test
	void 상품명이_없으면_상품_줄_없이_보낸다() {
		// given
		expectSuccess();

		// when
		analyzer.analyze(new ReviewSentimentInput(1L, null, "5kg 한 상자", "아삭하고 달아요"));

		// then
		assertThat(json(sentBodies.getFirst()).path("state").asString()).isEqualTo("옵션: 5kg 한 상자\n본문: 아삭하고 달아요");
	}

	@Test
	void 모델과_API_키를_인증_헤더로_보낸다() {
		// given
		server.expect(requestTo(ANALYZE_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer " + API_KEY))
			.andExpect(this::capture)
			.andRespond(withSuccess(answersResponse(validAnswers()), MediaType.APPLICATION_JSON));

		// when
		analyzer.analyze(input(1L));

		// then
		assertThat(json(sentBodies.getFirst()).path("model").asString()).isEqualTo(MODEL);
		server.verify();
	}

	@Test
	void 질문_7개마다_공통_지시문을_앞에_붙이고_선택지를_이름과_설명으로_보낸다() {
		// given
		JsonNode criteriaFile = criteriaFile();
		String instruction = criteriaFile.path("instruction").asString();
		String negativeDescription = criteriaFile.path("aspectChoices").values().stream()
			.filter(choice -> choice.path("value").asString().equals("negative"))
			.findFirst().orElseThrow()
			.path("description").asString();
		expectSuccess();

		// when
		analyzer.analyze(input(1L));

		// then
		JsonNode questions = json(sentBodies.getFirst()).path("questions");
		assertThat(questions.propertyNames()).containsExactlyElementsOf(QUESTION_KEYS);
		assertSoftly(softly -> {
			for (String key : QUESTION_KEYS) {
				softly.assertThat(questions.path(key).path("type").asString()).isEqualTo("choice");
				softly.assertThat(questions.path(key).path("instructions").asString()).startsWith(instruction + "\n");
			}
			softly.assertThat(questions.path("taste").path("criteria").path("negative").asString())
				.isEqualTo("부정: " + negativeDescription);
		});
	}

	@Test
	void 같은_리뷰_ID는_늘_같은_선택지_순서를_받는다() {
		// given
		expectSuccess();
		expectSuccess();

		// when
		analyzer.analyze(input(42L));
		analyzer.analyze(input(42L));

		// then
		assertThat(choiceOrders(sentBodies.get(1))).isEqualTo(choiceOrders(sentBodies.get(0)));
	}

	@Test
	void 다른_리뷰_ID는_분류_기준_선택지를_다른_순서로_섞어_받는다() {
		// given
		expectSuccess();
		expectSuccess();

		// when
		analyzer.analyze(input(1L));
		analyzer.analyze(input(2L));

		// then
		Map<String, List<String>> first = choiceOrders(sentBodies.get(0));
		Map<String, List<String>> second = choiceOrders(sentBodies.get(1));
		assertThat(first).isNotEqualTo(second);
		assertSoftly(softly -> {
			for (Map<String, List<String>> orders : List.of(first, second)) {
				softly.assertThat(orders.get("overall")).containsExactlyInAnyOrderElementsOf(OVERALL_CHOICES);
				for (String key : QUESTION_KEYS.subList(1, QUESTION_KEYS.size())) {
					softly.assertThat(orders.get(key)).containsExactlyInAnyOrderElementsOf(ASPECT_CHOICES);
				}
			}
		});
	}

	// 응답 해석

	@Test
	void 정상_응답을_전체_감성과_여섯_항목_감성으로_해석한다() {
		// given
		Map<String, String> answers = new LinkedHashMap<>();
		answers.put("overall", "mixed");
		answers.put("taste", "positive");
		answers.put("freshness", "negative");
		answers.put("condition", "neutral");
		answers.put("packaging", "not_mentioned");
		answers.put("delivery", "mixed");
		answers.put("price", "not_mentioned");
		// 묻지 않은 답이 더 와도 무시한다
		answers.put("seller", "positive");
		server.expect(requestTo(ANALYZE_URL)).andRespond(withSuccess(answersResponse(answers), MediaType.APPLICATION_JSON));

		// when
		ReviewSentimentAnalysis sut = analyzer.analyze(input(1L));

		// then
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.overall()).isEqualTo(Sentiment.MIXED);
			softly.assertThat(sut.aspects()).isEqualTo(Map.of(
				ReviewAspect.TASTE, AspectSentiment.POSITIVE,
				ReviewAspect.FRESHNESS, AspectSentiment.NEGATIVE,
				ReviewAspect.CONDITION, AspectSentiment.NEUTRAL,
				ReviewAspect.PACKAGING, AspectSentiment.NOT_MENTIONED,
				ReviewAspect.DELIVERY, AspectSentiment.MIXED,
				ReviewAspect.PRICE, AspectSentiment.NOT_MENTIONED));
			softly.assertThat(sut.criteriaVersion()).isEqualTo("v0.2");
			softly.assertThat(sut.model()).isEqualTo(MODEL);
		});
	}

	// 실패 구분

	@ParameterizedTest
	@MethodSource("재시도할_상태_코드")
	void 요청_제한과_서버_오류는_재시도할_실패다(HttpStatus status) {
		// given
		server.expect(requestTo(ANALYZE_URL)).andRespond(withStatus(status));

		// when & then
		assertThatThrownBy(() -> analyzer.analyze(input(1L)))
			.isInstanceOfSatisfying(ReviewAnalysisFailedException.class, e -> assertThat(e.isRetryable()).isTrue());
	}

	static Stream<HttpStatus> 재시도할_상태_코드() {
		return Stream.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE);
	}

	@ParameterizedTest
	@MethodSource("연결_오류")
	void 시간_초과와_연결_실패는_재시도할_실패다(IOException error) {
		// given
		server.expect(requestTo(ANALYZE_URL)).andRespond(withException(error));

		// when & then
		assertThatThrownBy(() -> analyzer.analyze(input(1L)))
			.isInstanceOfSatisfying(ReviewAnalysisFailedException.class, e -> assertThat(e.isRetryable()).isTrue());
	}

	static Stream<IOException> 연결_오류() {
		return Stream.of(new SocketTimeoutException("Read timed out"), new ConnectException("Connection refused"));
	}

	@ParameterizedTest
	@MethodSource("최종_실패할_상태_코드")
	void 요청_제한과_인증_거절이_아닌_4xx는_최종_실패다(HttpStatus status) {
		// given
		server.expect(requestTo(ANALYZE_URL)).andRespond(withStatus(status));

		// when & then
		assertThatThrownBy(() -> analyzer.analyze(input(1L)))
			.isInstanceOfSatisfying(ReviewAnalysisFailedException.class, e -> assertThat(e.isRetryable()).isFalse());
	}

	static Stream<HttpStatus> 최종_실패할_상태_코드() {
		return Stream.of(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND);
	}

	@ParameterizedTest
	@MethodSource("인증_거절_상태_코드")
	void 인증_거절은_분석기를_쓸_수_없는_실패다(HttpStatus status) {
		// given
		server.expect(requestTo(ANALYZE_URL)).andRespond(withStatus(status));

		// when & then
		assertThatThrownBy(() -> analyzer.analyze(input(1L)))
			.isInstanceOf(ReviewAnalyzerUnavailableException.class)
			.hasMessage("Jev가 인증을 거절했습니다. status=" + status.value());
	}

	static Stream<HttpStatus> 인증_거절_상태_코드() {
		return Stream.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
	}

	@ParameterizedTest
	@MethodSource("해석할_수_없는_응답")
	void 응답을_해석할_수_없으면_최종_실패다(String body) {
		// given
		server.expect(requestTo(ANALYZE_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		// when & then
		assertThatThrownBy(() -> analyzer.analyze(input(1L)))
			.isInstanceOfSatisfying(ReviewAnalysisFailedException.class, e -> assertThat(e.isRetryable()).isFalse());
	}

	static Stream<String> 해석할_수_없는_응답() {
		Map<String, String> missingPrice = validAnswers();
		missingPrice.remove("price");
		// 언급 없음은 항목 감성에만 있는 선택지라 전체 감성 답으로는 받을 수 없다
		Map<String, String> notInChoices = validAnswers();
		notInChoices.put("overall", "not_mentioned");
		String notChoiceType = answersResponse(validAnswers()).replaceFirst("\"type\":\"choice\"", "\"type\":\"text\"");
		return Stream.of(
			"{not json",
			"",
			"{\"model\":\"jev-1.14.0\"}",
			answersResponse(missingPrice),
			answersResponse(notInChoices),
			notChoiceType);
	}

	private void expectSuccess() {
		server.expect(requestTo(ANALYZE_URL))
			.andExpect(this::capture)
			.andRespond(withSuccess(answersResponse(validAnswers()), MediaType.APPLICATION_JSON));
	}

	private void capture(ClientHttpRequest request) {
		sentBodies.add(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8));
	}

	private static ReviewSentimentInput input(Long reviewId) {
		return new ReviewSentimentInput(reviewId, "청송 꿀사과", "5kg 한 상자", "아삭하고 달아요");
	}

	private static Map<String, String> validAnswers() {
		Map<String, String> answers = new LinkedHashMap<>();
		answers.put("overall", "positive");
		for (String key : QUESTION_KEYS.subList(1, QUESTION_KEYS.size())) {
			answers.put(key, "not_mentioned");
		}
		return answers;
	}

	// 실제 Jev 응답처럼 신뢰도·확률·사용량도 함께 담는다
	private static String answersResponse(Map<String, String> choices) {
		String answers = choices.entrySet().stream()
			.map(entry -> """
				"%s":{"type":"choice","choice":"%s","confidence":0.91,"probabilities":{"%s":0.91}}"""
				.formatted(entry.getKey(), entry.getValue(), entry.getValue()))
			.collect(Collectors.joining(","));
		return """
			{"model":"jev-1.14.0","answers":{%s},"usage":{"input_tokens":812,"output_tokens":0}}""".formatted(answers);
	}

	private static Map<String, List<String>> choiceOrders(String body) {
		Map<String, List<String>> orders = new LinkedHashMap<>();
		json(body).path("questions").properties()
			.forEach(question -> orders.put(question.getKey(), List.copyOf(question.getValue().path("criteria").propertyNames())));
		return orders;
	}

	private static JsonNode json(String body) {
		return JsonMapper.shared().readTree(body);
	}

	private static JsonNode criteriaFile() {
		try (InputStream input = new ClassPathResource("review-analysis/criteria.json").getInputStream()) {
			return JsonMapper.shared().readTree(input);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
