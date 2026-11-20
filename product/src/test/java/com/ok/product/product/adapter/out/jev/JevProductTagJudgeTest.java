package com.ok.product.product.adapter.out.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

import com.ok.product.config.JevProperties;
import com.ok.product.product.application.dto.ProductTagInput;
import com.ok.product.product.application.dto.ProductTagScores;
import com.ok.product.product.application.service.ProductTagList;
import com.ok.product.product.exception.ProductTagJudgeFailedException;
import com.ok.product.product.exception.ProductTagJudgeUnavailableException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 실제 Jev를 부르지 않고 가짜 HTTP 서버로 요청 본문이 평가 스크립트와 같은 모양인지, 응답 해석과 실패 구분을 확인한다
class JevProductTagJudgeTest {

	private static final String BASE_URL = "https://api.jev.test";
	private static final String JUDGE_URL = BASE_URL + "/v1/systemone";
	private static final String API_KEY = "test-jev-key";
	private static final String MODEL = "jev-1.14.0";

	private final List<String> sentBodies = new ArrayList<>();
	private MockRestServiceServer server;
	private JevProductTagJudge judge;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		JevProperties properties = new JevProperties(BASE_URL, API_KEY, MODEL, Duration.ofSeconds(3), Duration.ofSeconds(10));
		judge = new JevProductTagJudge(new JevTagClient(builder.build(), properties), new ProductTagList(), properties);
	}

	// 요청

	@Test
	void 상품명과_옵션명만_평가_스크립트와_같은_모양으로_보낸다() {
		// given
		JsonNode tagsFile = tagsFile();
		JsonNode thirdTag = tagsFile.path("tags").values().stream().skip(2).findFirst().orElseThrow();
		String expectedInstructions = tagsFile.path("instruction").asString() + "\n이 상품에 \""
			+ thirdTag.path("label").asString() + "\" 태그를 붙이나?\n판정 기준: " + thirdTag.path("description").asString();
		Map<String, String> expectedCriteria = tagsFile.path("choices").values().stream()
			.collect(Collectors.toMap(choice -> choice.path("value").asString(), choice -> choice.path("description").asString()));
		server.expect(requestTo(JUDGE_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer " + API_KEY))
			.andExpect(this::capture)
			.andRespond(withSuccess(response(answers(0.03)), MediaType.APPLICATION_JSON));

		// when
		judge.judge(new ProductTagInput(987654L, "제주 감귤", List.of("2kg 소과", "5kg 중과")));

		// then
		JsonNode body = json(sentBodies.getFirst());
		JsonNode question = body.path("questions").path("t2");
		Map<String, String> criteria = new LinkedHashMap<>();
		question.path("criteria").properties().forEach(entry -> criteria.put(entry.getKey(), entry.getValue().asString()));
		assertSoftly(softly -> {
			softly.assertThat(body.path("model").asString()).isEqualTo(MODEL);
			softly.assertThat(body.path("state").asString()).isEqualTo("상품: 제주 감귤\n옵션: 2kg 소과, 5kg 중과");
			softly.assertThat(sentBodies.getFirst()).doesNotContain("987654");
			softly.assertThat(List.copyOf(body.path("questions").propertyNames())).containsExactlyElementsOf(questionKeys());
			softly.assertThat(question.path("type").asString()).isEqualTo("choice");
			softly.assertThat(question.path("instructions").asString()).isEqualTo(expectedInstructions);
			softly.assertThat(criteria).isEqualTo(expectedCriteria);
		});
		server.verify();
	}

	@Test
	void 같은_상품은_늘_같은_선택지_순서를_받고_다른_상품은_다르게_섞인다() {
		// given
		for (int i = 0; i < 3; i++) {
			server.expect(requestTo(JUDGE_URL)).andExpect(this::capture)
				.andRespond(withSuccess(response(answers(0.03)), MediaType.APPLICATION_JSON));
		}

		// when
		judge.judge(new ProductTagInput(42L, "제주 감귤", List.of("2kg")));
		judge.judge(new ProductTagInput(42L, "제주 감귤", List.of("2kg")));
		judge.judge(new ProductTagInput(43L, "제주 감귤", List.of("2kg")));

		// then
		assertSoftly(softly -> {
			softly.assertThat(choiceOrders(sentBodies.get(1))).isEqualTo(choiceOrders(sentBodies.get(0)));
			softly.assertThat(choiceOrders(sentBodies.get(2))).isNotEqualTo(choiceOrders(sentBodies.get(0)));
		});
	}

	// 응답 해석

	@Test
	void 질문_순번의_해당_확률을_태그_ID로_꺼내_목록_버전과_모델을_함께_돌려준다() {
		// given
		Map<String, String> answers = answers(0.03);
		answers.put("t2", answer(0.95));
		server.expect(requestTo(JUDGE_URL)).andRespond(withSuccess(response(answers), MediaType.APPLICATION_JSON));

		// when
		ProductTagScores sut = judge.judge(new ProductTagInput(1L, "제주 감귤", List.of("2kg")));

		// then
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.probabilities()).hasSize(tagCount()).containsEntry("T03", 0.95).containsEntry("T01", 0.03);
			softly.assertThat(sut.appliedTagIds()).containsExactly("T03");
			softly.assertThat(sut.tagVersion()).isEqualTo("v3");
			softly.assertThat(sut.model()).isEqualTo(MODEL);
		});
	}

	@ParameterizedTest
	@MethodSource("해석할_수_없는_응답")
	void 응답을_해석할_수_없으면_최종_실패다(String body) {
		// given
		server.expect(requestTo(JUDGE_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		// when & then
		assertThatThrownBy(() -> judge.judge(new ProductTagInput(1L, "제주 감귤", List.of("2kg"))))
			.isInstanceOfSatisfying(ProductTagJudgeFailedException.class, e -> assertThat(e.isRetryable()).isFalse());
	}

	static Stream<String> 해석할_수_없는_응답() {
		Map<String, String> missingAnswer = answers(0.03);
		missingAnswer.remove("t5");
		Map<String, String> missingApplies = answers(0.03);
		missingApplies.put("t5", """
			{"type":"choice","choice":"not_applies","probabilities":{"not_applies":0.97}}""");
		Map<String, String> aboveOne = answers(0.03);
		aboveOne.put("t5", answer(1.2));
		return Stream.of("{not json", "{\"model\":\"jev-1.14.0\"}", response(missingAnswer), response(missingApplies),
			response(aboveOne));
	}

	// 실패 구분

	@ParameterizedTest
	@MethodSource("상태_코드별_재시도_여부")
	void 요청_제한과_서버_오류만_재시도할_실패다(HttpStatus status, boolean retryable) {
		// given
		server.expect(requestTo(JUDGE_URL)).andRespond(withStatus(status));

		// when & then
		assertThatThrownBy(() -> judge.judge(new ProductTagInput(1L, "제주 감귤", List.of("2kg"))))
			.isInstanceOfSatisfying(ProductTagJudgeFailedException.class,
				e -> assertThat(e.isRetryable()).isEqualTo(retryable));
	}

	static Stream<Arguments> 상태_코드별_재시도_여부() {
		return Stream.of(
			Arguments.of(HttpStatus.TOO_MANY_REQUESTS, true),
			Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, true),
			Arguments.of(HttpStatus.BAD_REQUEST, false));
	}

	@ParameterizedTest
	@MethodSource("인증_거절_상태_코드")
	void 인증_거절은_판정기를_쓸_수_없는_실패다(HttpStatus status) {
		// given
		server.expect(requestTo(JUDGE_URL)).andRespond(withStatus(status));

		// when & then
		assertThatThrownBy(() -> judge.judge(new ProductTagInput(1L, "제주 감귤", List.of("2kg"))))
			.isInstanceOf(ProductTagJudgeUnavailableException.class)
			.hasMessage("Jev가 인증을 거절했습니다. status=" + status.value());
	}

	static Stream<HttpStatus> 인증_거절_상태_코드() {
		return Stream.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
	}

	private void capture(ClientHttpRequest request) {
		sentBodies.add(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8));
	}

	private static Map<String, String> answers(double applies) {
		Map<String, String> answers = new LinkedHashMap<>();
		questionKeys().forEach(key -> answers.put(key, answer(applies)));
		return answers;
	}

	// 실제 Jev 응답처럼 고른 값·신뢰도·선택지별 확률을 함께 담는다
	private static String answer(double applies) {
		double notApplies = 1 - applies;
		String choice = applies >= notApplies ? "applies" : "not_applies";
		return """
			{"type":"choice","choice":"%s","confidence":%s,"probabilities":{"applies":%s,"not_applies":%s}}"""
			.formatted(choice, Math.max(applies, notApplies), applies, notApplies);
	}

	private static String response(Map<String, String> answers) {
		String body = answers.entrySet().stream()
			.map(entry -> "\"%s\":%s".formatted(entry.getKey(), entry.getValue()))
			.collect(Collectors.joining(","));
		return """
			{"model":"jev-1.14.0","answers":{%s}}""".formatted(body);
	}

	// 질문 키를 목록 클래스를 거치지 않고 파일의 태그 수로 만든다
	private static List<String> questionKeys() {
		return IntStream.range(0, tagCount()).mapToObj(i -> "t" + i).toList();
	}

	private static int tagCount() {
		return tagsFile().path("tags").size();
	}

	private static Map<String, List<String>> choiceOrders(String body) {
		Map<String, List<String>> orders = new LinkedHashMap<>();
		json(body).path("questions").properties()
			.forEach(question -> orders.put(question.getKey(), List.copyOf(question.getValue().path("criteria").propertyNames())));
		return orders;
	}

	private static JsonNode tagsFile() {
		try (InputStream input = new ClassPathResource("product-search/tags.json").getInputStream()) {
			return JsonMapper.shared().readTree(input);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static JsonNode json(String body) {
		return JsonMapper.shared().readTree(body);
	}
}
