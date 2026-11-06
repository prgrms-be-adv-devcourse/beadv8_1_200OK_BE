package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.review.domain.AspectSentiment;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.ReviewExpressedKeyword;
import com.ok.product.review.domain.Sentiment;
import com.ok.product.review.repository.ProductReviewRepository;
import com.ok.product.review.repository.ReviewAnalysisRequestRepository;
import com.ok.product.review.repository.ReviewKeywordRepository;
import com.ok.product.review.repository.ReviewSentimentRepository;
import com.ok.product.review.service.ReviewKeywordAnalysisFacade;
import com.ok.product.review.service.dto.ReviewablePurchase;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeOrderApiClient;
import com.ok.testsupport.FakeReviewKeywordAnalyzer;
import com.ok.testsupport.FakeReviewSentimentAnalyzer;
import com.ok.testsupport.TestAccessTokens;

// 상품은 과일이라 C01(맛있어요)·C02(달아요)·C22(아삭해요)·C25(식감이 좋아요)를 모두 묻는다.
// C22는 구체적인 식감 키워드라 C22를 표현하면 C25는 확률이 0.9를 넘어도 표현하지 않은 것으로 본다
class ReviewAnalysisAdminIntegrationTest extends AbstractIntegrationTest {

	private static final Long ADMIN_ID = 99L;
	private static final Long SELLER_ID = 10L;
	private static final Long BUYER_ID = 1L;
	private static final String KEYWORDS_URL = "/api/v1/admin/review-analysis/keywords";
	private static final String SENTIMENT_URL = "/api/v1/admin/review-analysis/sentiment";
	private static final String CONTENT = "달고 아삭해요";

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	@Autowired
	ProductRepository productRepository;

	@Autowired
	ProductReviewRepository productReviewRepository;

	@Autowired
	ReviewAnalysisRequestRepository analysisRequestRepository;

	@Autowired
	ReviewKeywordRepository reviewKeywordRepository;

	@Autowired
	ReviewSentimentRepository reviewSentimentRepository;

	@Autowired
	ReviewKeywordAnalysisFacade reviewKeywordAnalysisFacade;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	FakeOrderApiClient orderApiClient;

	@Autowired
	FakeReviewKeywordAnalyzer keywordAnalyzer;

	@Autowired
	FakeReviewSentimentAnalyzer sentimentAnalyzer;

	private MockMvc mockMvc;
	private Long productId;
	private Long optionId;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		clearReviews();
		productId = productRepository.save(product("청송 꿀사과")).getId();
		optionId = firstOptionId(productId);
	}

	// 다른 상품 통합 테스트가 상품을 지울 때 리뷰의 외래 키에 걸리지 않게 리뷰와 분석 결과를 남기지 않는다
	@AfterEach
	void tearDown() {
		clearReviews();
		orderApiClient.clear();
		keywordAnalyzer.clear();
		sentimentAnalyzer.clear();
	}

	// 키워드

	@Test
	void 관리자는_물은_키워드_전부를_확률_높은_순으로_판정과_함께_받는다() throws Exception {
		// given
		keywordAnalyzer.respond(CONTENT, Map.of("C02", 0.97, "C22", 0.95, "C25", 0.93, "C01", 0.5));

		// when
		ResultActions sut = analyze(KEYWORDS_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT);

		// then
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzed").value(true))
			.andExpect(jsonPath("$.keywordVersion").value("v0.3"))
			.andExpect(jsonPath("$.model").value(FakeReviewKeywordAnalyzer.MODEL))
			.andExpect(jsonPath("$.keywords[0].keywordId").value("C02"))
			.andExpect(jsonPath("$.keywords[0].phrase").value("달아요"))
			.andExpect(jsonPath("$.keywords[0].aspect").value("TASTE"))
			.andExpect(jsonPath("$.keywords[0].probability").value(0.97))
			.andExpect(jsonPath("$.keywords[0].expressed").value(true))
			.andExpect(jsonPath("$.keywords[0].excludedByTextureRule").value(false))
			.andExpect(jsonPath("$.keywords[1].keywordId").value("C22"))
			.andExpect(jsonPath("$.keywords[1].expressed").value(true))
			.andExpect(jsonPath("$.keywords[2].keywordId").value("C25"))
			.andExpect(jsonPath("$.keywords[2].expressed").value(false))
			.andExpect(jsonPath("$.keywords[2].excludedByTextureRule").value(true))
			.andExpect(jsonPath("$.keywords[3].keywordId").value("C01"))
			.andExpect(jsonPath("$.keywords[3].expressed").value(false))
			.andExpect(jsonPath("$.keywords[3].excludedByTextureRule").value(false));
	}

	@Test
	void 같은_본문이면_운영_작업자가_저장하는_표현한_키워드와_expressed_키워드가_같다() throws Exception {
		// given
		keywordAnalyzer.respond(CONTENT, Map.of("C02", 0.97, "C22", 0.9, "C25", 0.99, "C01", 0.89));
		long reviewId = writeReview(100L, CONTENT);
		reviewKeywordAnalysisFacade.analyzePending();
		Set<String> saved = transactionTemplate.execute(status -> reviewKeywordRepository.findByReviewId(reviewId).orElseThrow()
			.getExpressedKeywords().stream().map(ReviewExpressedKeyword::getKeywordId).collect(Collectors.toSet()));

		// when
		String body = analyze(KEYWORDS_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		// then
		List<String> sut = JsonPath.read(body, "$.keywords[?(@.expressed == true)].keywordId");
		assertThat(new HashSet<>(sut)).isEqualTo(saved).containsExactlyInAnyOrder("C02", "C22");
	}

	// 감성

	@Test
	void 관리자는_전체_감성과_여섯_분석_항목_감성을_받는다() throws Exception {
		// given
		sentimentAnalyzer.respond(CONTENT, Sentiment.MIXED,
			Map.of(ReviewAspect.TASTE, AspectSentiment.POSITIVE, ReviewAspect.DELIVERY, AspectSentiment.NEGATIVE));

		// when
		ResultActions sut = analyze(SENTIMENT_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT);

		// then
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzed").value(true))
			.andExpect(jsonPath("$.criteriaVersion").value("v0.2"))
			.andExpect(jsonPath("$.model").value(FakeReviewSentimentAnalyzer.MODEL))
			.andExpect(jsonPath("$.overall").value("MIXED"))
			.andExpect(jsonPath("$.aspects.TASTE").value("POSITIVE"))
			.andExpect(jsonPath("$.aspects.FRESHNESS").value("NOT_MENTIONED"))
			.andExpect(jsonPath("$.aspects.CONDITION").value("NOT_MENTIONED"))
			.andExpect(jsonPath("$.aspects.PACKAGING").value("NOT_MENTIONED"))
			.andExpect(jsonPath("$.aspects.DELIVERY").value("NEGATIVE"))
			.andExpect(jsonPath("$.aspects.PRICE").value("NOT_MENTIONED"));
	}

	// 공통

	@ParameterizedTest
	@MethodSource("분석_API")
	void 내용_없는_본문은_분석기를_부르지_않고_분석하지_않았다고_돌려준다(String url) throws Exception {
		// when
		ResultActions sut = analyze(url, ADMIN_ID, "ADMIN", productId, optionId, "ㅋㅋㅋ!! 123");

		// then
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzed").value(false))
			.andExpect(jsonPath("$.model").value(nullValue()));
		assertSoftly(softly -> {
			softly.assertThat(keywordAnalyzer.analyzedContents()).isEmpty();
			softly.assertThat(sentimentAnalyzer.analyzedContents()).isEmpty();
		});
	}

	static Stream<String> 분석_API() {
		return Stream.of(KEYWORDS_URL, SENTIMENT_URL);
	}

	@Test
	void 내용_없는_본문의_키워드_결과는_빈_목록이다() throws Exception {
		// when & then
		analyze(KEYWORDS_URL, ADMIN_ID, "ADMIN", productId, optionId, "ㅠㅠ")
			.andExpect(jsonPath("$.keywords", empty()));
	}

	@Test
	void 분석해도_리뷰와_분석_관련_표의_행_수가_그대로다() throws Exception {
		// given
		keywordAnalyzer.respond(CONTENT, Map.of("C02", 0.97));
		sentimentAnalyzer.respond(CONTENT, Sentiment.POSITIVE, Map.of());
		writeReview(100L, "이미 있는 리뷰예요");
		List<Long> before = rowCounts();

		// when
		analyze(KEYWORDS_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT).andExpect(status().isOk());
		analyze(SENTIMENT_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT).andExpect(status().isOk());

		// then
		assertThat(rowCounts()).isEqualTo(before);
	}

	// 실패

	@Test
	void 키워드_분석이_실패해도_감성_분석은_성공한다() throws Exception {
		// given
		keywordAnalyzer.fail(CONTENT);
		sentimentAnalyzer.respond(CONTENT, Sentiment.POSITIVE, Map.of());

		// when & then
		analyze(KEYWORDS_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT)
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.type", endsWith("/product/review-analysis-failed")));
		analyze(SENTIMENT_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.overall").value("POSITIVE"));
	}

	@Test
	void 감성_분석이_실패해도_키워드_분석은_성공한다() throws Exception {
		// given
		sentimentAnalyzer.failRetryable(CONTENT);
		keywordAnalyzer.respond(CONTENT, Map.of("C02", 0.97));

		// when & then
		analyze(SENTIMENT_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT)
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.type", endsWith("/product/review-analysis-failed")));
		analyze(KEYWORDS_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keywords[0].keywordId").value("C02"));
	}

	@Test
	void 분석기가_인증을_거절하면_503이다() throws Exception {
		// given
		keywordAnalyzer.failUnavailable(CONTENT);
		sentimentAnalyzer.failUnavailable(CONTENT);

		// when & then
		for (String url : List.of(KEYWORDS_URL, SENTIMENT_URL)) {
			analyze(url, ADMIN_ID, "ADMIN", productId, optionId, CONTENT)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type", endsWith("/product/review-analyzer-unavailable")));
		}
	}

	// 권한

	@ParameterizedTest
	@MethodSource("관리자가_아닌_역할")
	void 관리자가_아니면_403이다(String url, String role) throws Exception {
		// when & then
		analyze(url, BUYER_ID, role, productId, optionId, CONTENT)
			.andExpect(status().isForbidden());
		assertThat(keywordAnalyzer.analyzedContents()).isEmpty();
	}

	static Stream<Arguments> 관리자가_아닌_역할() {
		return Stream.of(
			Arguments.of(KEYWORDS_URL, "BUYER"),
			Arguments.of(KEYWORDS_URL, "SELLER"),
			Arguments.of(SENTIMENT_URL, "BUYER"),
			Arguments.of(SENTIMENT_URL, "SELLER"));
	}

	@ParameterizedTest
	@MethodSource("분석_API")
	void 로그인하지_않으면_401이다(String url) throws Exception {
		// when & then
		mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body(productId, optionId, CONTENT)))
			.andExpect(status().isUnauthorized());
	}

	// 검증

	@ParameterizedTest
	@MethodSource("분석_API")
	void 없는_상품이면_404다(String url) throws Exception {
		// when & then
		analyze(url, ADMIN_ID, "ADMIN", productId + 1_000, optionId, CONTENT)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type", endsWith("/product/product-not-found")));
	}

	@Test
	void 삭제된_상품이면_404다() throws Exception {
		// given
		transactionTemplate.executeWithoutResult(
			status -> productRepository.findById(productId).orElseThrow().delete(LocalDateTime.of(2026, 11, 6, 0, 0)));

		// when & then
		analyze(KEYWORDS_URL, ADMIN_ID, "ADMIN", productId, optionId, CONTENT)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type", endsWith("/product/product-not-found")));
	}

	@ParameterizedTest
	@MethodSource("분석_API")
	void 다른_상품의_옵션이면_400이다(String url) throws Exception {
		// given
		Long otherOptionId = firstOptionId(productRepository.save(product("영주 부사")).getId());

		// when & then
		analyze(url, ADMIN_ID, "ADMIN", productId, otherOptionId, CONTENT)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type", endsWith("/product/invalid-review-option")));
	}

	@ParameterizedTest
	@MethodSource("잘못된_본문")
	void 본문이_비었거나_2000자를_넘으면_422다(String content) throws Exception {
		// when & then
		analyze(KEYWORDS_URL, ADMIN_ID, "ADMIN", productId, optionId, content)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.type", endsWith("/validation-failed")))
			.andExpect(jsonPath("$.errors[*].path", contains("content")));
	}

	static Stream<String> 잘못된_본문() {
		return Stream.of("", " ", "가".repeat(2001));
	}

	private ResultActions analyze(String url, Long memberId, String role, Long productId, Long optionId, String content)
		throws Exception {
		MockHttpServletRequestBuilder request = post(url)
			.header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(productId, optionId, content));
		return mockMvc.perform(request);
	}

	private static String body(Long productId, Long optionId, String content) {
		return """
			{"productId": %d, "optionId": %d, "content": "%s"}
			""".formatted(productId, optionId, content);
	}

	private List<Long> rowCounts() {
		return List.of(productReviewRepository.count(), analysisRequestRepository.count(),
			reviewKeywordRepository.count(), reviewSentimentRepository.count());
	}

	private long writeReview(long orderId, String content) throws Exception {
		orderApiClient.add(BUYER_ID, productId, new ReviewablePurchase(orderId, orderId * 10, optionId));
		String body = mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"orderItemId": %d, "rating": 9, "content": "%s", "mediaUrls": []}
					""".formatted(orderId * 10, content)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number)JsonPath.read(body, "$.reviewId")).longValue();
	}

	private Long firstOptionId(Long productId) {
		return transactionTemplate.execute(
			status -> productRepository.findById(productId).orElseThrow().getOptions().getFirst().getId());
	}

	private void clearReviews() {
		reviewKeywordRepository.deleteAll();
		reviewSentimentRepository.deleteAll();
		analysisRequestRepository.deleteAll();
		productReviewRepository.deleteAll();
	}

	// 관리자 테스트는 숨긴 상품도 받으므로 노출 상태로 바꾸지 않는다
	private static Product product(String name) {
		return Product.create(SELLER_ID, name, "<p>설명</p>", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 10)), List.of("product/a.jpg"));
	}
}
