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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewAnalyzerUnavailableException;
import com.ok.product.review.client.ReviewKeywordList;
import com.ok.product.review.service.dto.ReviewKeywordAnalysis;
import com.ok.product.review.service.dto.ReviewKeywordInput;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 실제 Jev를 부르지 않고 가짜 HTTP 서버로 요청 본문과 응답 해석, 실패 구분을 확인한다
class JevReviewKeywordAnalyzerTest {

	private static final String BASE_URL = "https://api.jev.test";
	private static final String ANALYZE_URL = BASE_URL + "/v1/systemone";
	private static final String API_KEY = "test-jev-key";
	private static final String MODEL = "jev-1.14.0";

	private final List<String> sentBodies = new ArrayList<>();
	private MockRestServiceServer server;
	private JevReviewKeywordAnalyzer analyzer;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		JevProperties properties = new JevProperties(BASE_URL, API_KEY, MODEL, Duration.ofSeconds(3), Duration.ofSeconds(10));
		analyzer = new JevReviewKeywordAnalyzer(new JevClient(builder.build(), properties), new ReviewKeywordList(), properties);
	}

	// 요청

	@Test
	void 상품명과_본문만_보내고_리뷰_ID는_보내지_않는다() {
		// given
		expectSuccess(ProductCategory.FRUIT);

		// when
		analyzer.analyze(new ReviewKeywordInput(987654L, "청송 꿀사과", "아삭하고 달아요", ProductCategory.FRUIT));

		// then
		String body = sentBodies.getFirst();
		assertSoftly(softly -> {
			softly.assertThat(json(body).path("state").asString()).isEqualTo("상품: 청송 꿀사과\n본문: 아삭하고 달아요");
			softly.assertThat(body).doesNotContain("987654");
		});
	}

	@ParameterizedTest
	@MethodSource("빈_상품명")
	void 상품명이_없으면_상품_줄_없이_보낸다(String productName) {
		// given
		expectSuccess(ProductCategory.FRUIT);

		// when
		analyzer.analyze(new ReviewKeywordInput(1L, productName, "아삭하고 달아요", ProductCategory.FRUIT));

		// then
		assertThat(json(sentBodies.getFirst()).path("state").asString()).isEqualTo("본문: 아삭하고 달아요");
	}

	static Stream<String> 빈_상품명() {
		return Stream.of(null, "", " ");
	}

	@Test
	void 모델과_API_키를_인증_헤더로_보낸다() {
		// given
		server.expect(requestTo(ANALYZE_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer " + API_KEY))
			.andExpect(this::capture)
			.andRespond(withSuccess(validResponse(ProductCategory.FRUIT), MediaType.APPLICATION_JSON));

		// when
		analyzer.analyze(input(1L, ProductCategory.FRUIT));

		// then
		assertThat(json(sentBodies.getFirst()).path("model").asString()).isEqualTo(MODEL);
		server.verify();
	}

	@ParameterizedTest
	@MethodSource("분류별_묻는_키워드와_묻지_않는_키워드")
	void 상품_분류에_묻는_키워드만_목록_순서대로_질문으로_보낸다(ProductCategory category, String askedId, String notAskedId) {
		// given
		expectSuccess(category);

		// when
		analyzer.analyze(input(1L, category));

		// then
		List<String> questionKeys = List.copyOf(json(sentBodies.getFirst()).path("questions").propertyNames());
		assertSoftly(softly -> {
			softly.assertThat(questionKeys).containsExactlyElementsOf(keywordIdsInFile(category));
			softly.assertThat(questionKeys).contains(askedId).doesNotContain(notAskedId);
		});
	}

	static Stream<Arguments> 분류별_묻는_키워드와_묻지_않는_키워드() {
		return Stream.of(
			Arguments.of(ProductCategory.FRUIT, "F02", "P01"),
			Arguments.of(ProductCategory.GRAIN, "C04", "F02"));
	}

	@Test
	void 질문마다_공통_지시문과_키워드_문구_판정_기준을_실험과_같은_모양으로_보낸다() {
		// given
		JsonNode keywordsFile = keywordsFile();
		JsonNode keyword = keywordsFile.path("keywords").values().stream()
			.filter(node -> node.path("id").asString().equals("F02"))
			.findFirst().orElseThrow();
		String expectedInstructions = keywordsFile.path("instruction").asString()
			+ "\n이 리뷰는 \"" + keyword.path("phrase").asString() + "\"라고 말하나?\n판정 기준: " + keyword.path("guide").asString();
		Map<String, String> expectedCriteria = keywordsFile.path("choices").values().stream()
			.collect(Collectors.toMap(choice -> choice.path("value").asString(), choice -> choice.path("description").asString()));
		expectSuccess(ProductCategory.FRUIT);

		// when
		analyzer.analyze(input(1L, ProductCategory.FRUIT));

		// then
		JsonNode question = json(sentBodies.getFirst()).path("questions").path("F02");
		Map<String, String> criteria = new LinkedHashMap<>();
		question.path("criteria").properties().forEach(entry -> criteria.put(entry.getKey(), entry.getValue().asString()));
		assertSoftly(softly -> {
			softly.assertThat(question.path("type").asString()).isEqualTo("choice");
			softly.assertThat(question.path("instructions").asString()).isEqualTo(expectedInstructions);
			softly.assertThat(criteria).isEqualTo(expectedCriteria);
		});
	}

	@Test
	void 같은_리뷰_ID는_늘_같은_선택지_순서를_받는다() {
		// given
		expectSuccess(ProductCategory.FRUIT);
		expectSuccess(ProductCategory.FRUIT);

		// when
		analyzer.analyze(input(42L, ProductCategory.FRUIT));
		analyzer.analyze(input(42L, ProductCategory.FRUIT));

		// then
		assertThat(choiceOrders(sentBodies.get(1))).isEqualTo(choiceOrders(sentBodies.get(0)));
	}

	@Test
	void 다른_리뷰_ID는_선택지를_다른_순서로_섞어_받는다() {
		// given
		expectSuccess(ProductCategory.FRUIT);
		expectSuccess(ProductCategory.FRUIT);

		// when
		analyzer.analyze(input(1L, ProductCategory.FRUIT));
		analyzer.analyze(input(2L, ProductCategory.FRUIT));

		// then
		Map<String, List<String>> first = choiceOrders(sentBodies.get(0));
		Map<String, List<String>> second = choiceOrders(sentBodies.get(1));
		assertThat(first).isNotEqualTo(second);
		assertSoftly(softly -> {
			for (Map<String, List<String>> orders : List.of(first, second)) {
				orders.values().forEach(order ->
					softly.assertThat(order).containsExactlyInAnyOrder("mentioned", "not_mentioned"));
			}
		});
	}

	// 응답 해석

	@Test
	void 키워드마다_말함_확률을_꺼내_목록_버전과_모델을_함께_돌려준다() {
		// given
		Map<String, String> answers = validAnswers(ProductCategory.FRUIT);
		answers.put("F02", answer(0.97));
		answers.put("C22", answer(0.42));
		// 묻지 않은 답이 더 와도 무시한다
		answers.put("P01", answer(0.99));
		server.expect(requestTo(ANALYZE_URL)).andRespond(withSuccess(response(answers), MediaType.APPLICATION_JSON));

		// when
		ReviewKeywordAnalysis sut = analyzer.analyze(input(1L, ProductCategory.FRUIT));

		// then
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.probabilities().keySet()).containsExactlyInAnyOrderElementsOf(keywordIdsInFile(ProductCategory.FRUIT));
			softly.assertThat(sut.probabilities()).containsEntry("F02", 0.97).containsEntry("C22", 0.42).containsEntry("C01", 0.03);
			softly.assertThat(sut.keywordVersion()).isEqualTo(keywordsFile().path("version").asString());
			softly.assertThat(sut.model()).isEqualTo(MODEL);
		});
	}

	// 실패 구분

	@ParameterizedTest
	@MethodSource("해석할_수_없는_응답")
	void 응답을_해석할_수_없으면_최종_실패다(String body) {
		// given
		server.expect(requestTo(ANALYZE_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		// when & then
		assertThatThrownBy(() -> analyzer.analyze(input(1L, ProductCategory.FRUIT)))
			.isInstanceOfSatisfying(ReviewAnalysisFailedException.class, e -> assertThat(e.isRetryable()).isFalse());
	}

	static Stream<String> 해석할_수_없는_응답() {
		Map<String, String> missingAnswer = validAnswers(ProductCategory.FRUIT);
		missingAnswer.remove("F02");
		Map<String, String> missingProbabilities = validAnswers(ProductCategory.FRUIT);
		missingProbabilities.put("F02", """
			{"type":"choice","choice":"mentioned","confidence":0.97}""");
		Map<String, String> missingMentioned = validAnswers(ProductCategory.FRUIT);
		missingMentioned.put("F02", """
			{"type":"choice","choice":"not_mentioned","confidence":0.97,"probabilities":{"not_mentioned":0.97}}""");
		Map<String, String> aboveOne = validAnswers(ProductCategory.FRUIT);
		aboveOne.put("F02", answer(1.2));
		Map<String, String> belowZero = validAnswers(ProductCategory.FRUIT);
		belowZero.put("F02", answer(-0.1));
		return Stream.of(
			"{not json",
			"",
			"{\"model\":\"jev-1.14.0\"}",
			response(missingAnswer),
			response(missingProbabilities),
			response(missingMentioned),
			response(aboveOne),
			response(belowZero));
	}

	@ParameterizedTest
	@MethodSource("재시도할_상태_코드")
	void 요청_제한과_서버_오류는_재시도할_실패다(HttpStatus status) {
		// given
		server.expect(requestTo(ANALYZE_URL)).andRespond(withStatus(status));

		// when & then
		assertThatThrownBy(() -> analyzer.analyze(input(1L, ProductCategory.FRUIT)))
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
		assertThatThrownBy(() -> analyzer.analyze(input(1L, ProductCategory.FRUIT)))
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
		assertThatThrownBy(() -> analyzer.analyze(input(1L, ProductCategory.FRUIT)))
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
		assertThatThrownBy(() -> analyzer.analyze(input(1L, ProductCategory.FRUIT)))
			.isInstanceOf(ReviewAnalyzerUnavailableException.class)
			.hasMessage("Jev가 인증을 거절했습니다. status=" + status.value());
	}

	static Stream<HttpStatus> 인증_거절_상태_코드() {
		return Stream.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
	}

	private void expectSuccess(ProductCategory category) {
		server.expect(requestTo(ANALYZE_URL))
			.andExpect(this::capture)
			.andRespond(withSuccess(validResponse(category), MediaType.APPLICATION_JSON));
	}

	private void capture(ClientHttpRequest request) {
		sentBodies.add(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8));
	}

	private static ReviewKeywordInput input(Long reviewId, ProductCategory category) {
		return new ReviewKeywordInput(reviewId, "청송 꿀사과", "아삭하고 달아요", category);
	}

	private static String validResponse(ProductCategory category) {
		return response(validAnswers(category));
	}

	private static Map<String, String> validAnswers(ProductCategory category) {
		Map<String, String> answers = new LinkedHashMap<>();
		keywordIdsInFile(category).forEach(id -> answers.put(id, answer(0.03)));
		return answers;
	}

	// 실제 Jev 응답처럼 고른 값·신뢰도·선택지별 확률을 함께 담는다
	private static String answer(double mentioned) {
		double notMentioned = 1 - mentioned;
		String choice = mentioned >= notMentioned ? "mentioned" : "not_mentioned";
		return """
			{"type":"choice","choice":"%s","confidence":%s,"probabilities":{"mentioned":%s,"not_mentioned":%s}}"""
			.formatted(choice, Math.max(mentioned, notMentioned), mentioned, notMentioned);
	}

	private static String response(Map<String, String> answers) {
		String body = answers.entrySet().stream()
			.map(entry -> "\"%s\":%s".formatted(entry.getKey(), entry.getValue()))
			.collect(Collectors.joining(","));
		return """
			{"model":"jev-1.14.0","answers":{%s},"usage":{"input_tokens":4210,"output_tokens":0}}""".formatted(body);
	}

	// 분석기가 묻는 키워드를 목록 클래스를 거치지 않고 파일에서 바로 고른다
	private static List<String> keywordIdsInFile(ProductCategory category) {
		return keywordsFile().path("keywords").values().stream()
			.filter(keyword -> keyword.path("categories").values().stream()
				.map(JsonNode::asString)
				.anyMatch(name -> name.equals("ALL") || name.equals(category.name())))
			.map(keyword -> keyword.path("id").asString())
			.toList();
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

	private static JsonNode keywordsFile() {
		try (InputStream input = new ClassPathResource("review-analysis/keywords.json").getInputStream()) {
			return JsonMapper.shared().readTree(input);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
