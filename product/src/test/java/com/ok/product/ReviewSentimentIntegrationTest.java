package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.review.domain.AspectSentiment;
import com.ok.product.review.domain.ReviewAnalysisRequest;
import com.ok.product.review.domain.ReviewAnalysisStatus;
import com.ok.product.review.domain.ReviewAnalysisType;
import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.ReviewAspectSentiment;
import com.ok.product.review.domain.ReviewSentiment;
import com.ok.product.review.domain.Sentiment;
import com.ok.product.review.repository.ProductReviewRepository;
import com.ok.product.review.repository.ReviewAnalysisRequestRepository;
import com.ok.product.review.repository.ReviewSentimentRepository;
import com.ok.product.review.service.ProductReviewService;
import com.ok.product.review.service.ReviewSentimentAnalysisFacade;
import com.ok.product.review.service.dto.ProductReviewCreateCommand;
import com.ok.product.review.service.dto.ReviewablePurchase;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeOrderApiClient;
import com.ok.testsupport.FakeReviewSentimentAnalyzer;
import com.ok.testsupport.TestAccessTokens;

// 작업자 스케줄러는 설정이 없어 꺼져 있다. 테스트가 작업자 진입점(analyzePending)을 직접 부른다
class ReviewSentimentIntegrationTest extends AbstractIntegrationTest {

	private static final Long SELLER_ID = 10L;
	private static final Long OTHER_SELLER_ID = 11L;
	private static final Long BUYER_ID = 1L;
	private static final String SUMMARY_URL = "/api/v1/sellers/me/products/{productId}/review-sentiments";

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
	ReviewSentimentRepository reviewSentimentRepository;

	@Autowired
	ProductReviewService productReviewService;

	@Autowired
	ReviewSentimentAnalysisFacade reviewSentimentAnalysisFacade;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	FakeOrderApiClient orderApiClient;

	@Autowired
	FakeReviewSentimentAnalyzer analyzer;

	private MockMvc mockMvc;
	private Long productId;
	private Long optionId;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		clearReviews();
		productId = productRepository.save(product(SELLER_ID)).getId();
		optionId = transactionTemplate.execute(
			status -> productRepository.findById(productId).orElseThrow().getOptions().getFirst().getId());
	}

	// 다른 상품 통합 테스트가 상품을 지울 때 리뷰의 외래 키에 걸리지 않게 리뷰를 남기지 않는다
	@AfterEach
	void tearDown() {
		clearReviews();
		orderApiClient.clear();
		analyzer.clear();
	}

	@Test
	void 리뷰를_등록하면_감성_분석_요청이_대기로_생긴다() throws Exception {
		// given
		LocalDateTime before = LocalDateTime.now();

		// when
		long reviewId = writeReview(100L, "달고 맛있어요");

		// then
		List<ReviewAnalysisRequest> requests = analysisRequestRepository.findAll();
		assertThat(requests).hasSize(1);
		ReviewAnalysisRequest sut = requests.getFirst();
		assertSoftly(softly -> {
			softly.assertThat(sut.getReviewId()).isEqualTo(reviewId);
			softly.assertThat(sut.getAnalysisType()).isEqualTo(ReviewAnalysisType.SENTIMENT);
			softly.assertThat(sut.getStatus()).isEqualTo(ReviewAnalysisStatus.PENDING);
			softly.assertThat(sut.getAttemptCount()).isZero();
			softly.assertThat(sut.getNextAttemptAt()).isBetween(before, LocalDateTime.now());
		});
	}

	@Test
	void 리뷰_저장이_롤백되면_분석_요청도_남지_않는다() {
		// given
		ProductReviewCreateCommand command = new ProductReviewCreateCommand(orderItemId(100L), 9, "달고 맛있어요", List.of());

		// when: 리뷰를 쓴 트랜잭션이 롤백된다
		transactionTemplate.executeWithoutResult(status -> {
			productReviewService.create(BUYER_ID, productId, purchase(100L), command);
			status.setRollbackOnly();
		});

		// then
		assertSoftly(softly -> {
			softly.assertThat(productReviewRepository.count()).isZero();
			softly.assertThat(analysisRequestRepository.count()).isZero();
		});
	}

	@Test
	void 작업자를_한_번_실행하면_분석_결과가_저장되고_요청이_완료된다() throws Exception {
		// given
		analyzer.respond("달고 맛있는데 배송이 늦었어요", Sentiment.POSITIVE,
			Map.of(ReviewAspect.TASTE, AspectSentiment.POSITIVE, ReviewAspect.DELIVERY, AspectSentiment.NEGATIVE));
		long reviewId = writeReview(100L, "달고 맛있는데 배송이 늦었어요");

		// when
		reviewSentimentAnalysisFacade.analyzePending();

		// then
		LocalDateTime reviewUpdatedAt = productReviewRepository.findById(reviewId).orElseThrow().getUpdatedAt();
		ReviewAnalysisRequest request = analysisRequestRepository.findAll().getFirst();
		SavedSentiment sut = transactionTemplate.execute(status -> SavedSentiment.from(reviewSentimentRepository.findAll().getFirst()));
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(request.getStatus()).isEqualTo(ReviewAnalysisStatus.COMPLETED);
			softly.assertThat(sut.reviewId()).isEqualTo(reviewId);
			softly.assertThat(sut.reviewUpdatedAt()).isEqualTo(reviewUpdatedAt);
			softly.assertThat(sut.overall()).isEqualTo(Sentiment.POSITIVE);
			// 분류 기준 버전은 product 리소스의 criteria.json(v0.2)에서 읽은 값이다
			softly.assertThat(sut.criteriaVersion()).isEqualTo("v0.2");
			softly.assertThat(sut.model()).isEqualTo(FakeReviewSentimentAnalyzer.MODEL);
			// 언급 없음인 네 항목은 행이 없다
			softly.assertThat(sut.aspects()).isEqualTo(
				Map.of(ReviewAspect.TASTE, Sentiment.POSITIVE, ReviewAspect.DELIVERY, Sentiment.NEGATIVE));
		});
	}

	@Test
	void 감성_집계는_분석_완료_리뷰의_전체_감성과_항목별_분모와_개수를_돌려준다() throws Exception {
		// given
		analyzer.respond("달고 맛있는데 배송이 늦었어요", Sentiment.POSITIVE,
			Map.of(ReviewAspect.TASTE, AspectSentiment.POSITIVE, ReviewAspect.DELIVERY, AspectSentiment.NEGATIVE));
		analyzer.respond("맛은 좋은데 너무 비싸요", Sentiment.MIXED,
			Map.of(ReviewAspect.TASTE, AspectSentiment.POSITIVE, ReviewAspect.PRICE, AspectSentiment.NEGATIVE));
		analyzer.respond("그냥 그래요", Sentiment.NEUTRAL, Map.of(ReviewAspect.TASTE, AspectSentiment.NEUTRAL));
		writeReview(100L, "달고 맛있는데 배송이 늦었어요");
		writeReview(101L, "맛은 좋은데 너무 비싸요");
		writeReview(102L, "그냥 그래요");
		reviewSentimentAnalysisFacade.analyzePending();

		// when
		ResultActions sut = getSummary(SELLER_ID, productId);

		// then: 언급 없음은 항목 분모에 들지 않고, 언급한 리뷰가 없는 항목도 0으로 고정 순서에 나온다
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzedReviewCount").value(3))
			.andExpect(jsonPath("$.overall.positive").value(1))
			.andExpect(jsonPath("$.overall.negative").value(0))
			.andExpect(jsonPath("$.overall.neutral").value(1))
			.andExpect(jsonPath("$.overall.mixed").value(1))
			.andExpect(jsonPath("$.aspects[*].aspect")
				.value(contains("TASTE", "FRESHNESS", "CONDITION", "PACKAGING", "DELIVERY", "PRICE")))
			.andExpect(jsonPath("$.aspects[*].mentionedReviewCount").value(contains(3, 0, 0, 0, 1, 1)))
			.andExpect(jsonPath("$.aspects[*].sentiments.positive").value(contains(2, 0, 0, 0, 0, 0)))
			.andExpect(jsonPath("$.aspects[*].sentiments.negative").value(contains(0, 0, 0, 0, 1, 1)))
			.andExpect(jsonPath("$.aspects[*].sentiments.neutral").value(contains(1, 0, 0, 0, 0, 0)))
			.andExpect(jsonPath("$.aspects[*].sentiments.mixed").value(contains(0, 0, 0, 0, 0, 0)));
	}

	@Test
	void 분석을_기다리는_리뷰와_최종_실패한_리뷰는_집계에_들지_않는다() throws Exception {
		// given: 분석 성공 1건, 최종 실패 1건을 작업자가 처리한 뒤 대기 리뷰 1건을 더 쓴다
		analyzer.respond("달고 맛있어요", Sentiment.POSITIVE, Map.of(ReviewAspect.TASTE, AspectSentiment.POSITIVE));
		analyzer.fail("맛이 없어요");
		analyzer.respond("상해서 왔어요", Sentiment.NEGATIVE, Map.of(ReviewAspect.FRESHNESS, AspectSentiment.NEGATIVE));
		writeReview(100L, "달고 맛있어요");
		long failedReviewId = writeReview(101L, "맛이 없어요");
		reviewSentimentAnalysisFacade.analyzePending();
		writeReview(102L, "상해서 왔어요");

		// when
		ResultActions sut = getSummary(SELLER_ID, productId);

		// then
		assertThat(statusOf(failedReviewId)).isEqualTo(ReviewAnalysisStatus.FAILED);
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzedReviewCount").value(1))
			.andExpect(jsonPath("$.overall.positive").value(1))
			.andExpect(jsonPath("$.overall.negative").value(0))
			.andExpect(jsonPath("$.aspects[*].mentionedReviewCount").value(contains(1, 0, 0, 0, 0, 0)));
	}

	@Test
	void 다른_판매자의_상품을_조회하면_404다() throws Exception {
		// when & then
		getSummary(OTHER_SELLER_ID, productId)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type", endsWith("/product/product-not-found")));
	}

	@Test
	void 없는_상품을_조회하면_404다() throws Exception {
		// when & then
		getSummary(SELLER_ID, productId + 1_000)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type", endsWith("/product/product-not-found")));
	}

	@Test
	void 삭제된_상품을_조회하면_404다() throws Exception {
		// given
		Product deleted = product(SELLER_ID);
		deleted.delete(LocalDateTime.now());
		Long deletedProductId = productRepository.save(deleted).getId();

		// when & then
		getSummary(SELLER_ID, deletedProductId)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type", endsWith("/product/product-not-found")));
	}

	// 분석 결과의 항목 감성은 지연 로딩이라 트랜잭션 안에서 꺼내 둔다
	private record SavedSentiment(
		Long reviewId, LocalDateTime reviewUpdatedAt, Sentiment overall, String criteriaVersion, String model,
		Map<ReviewAspect, Sentiment> aspects
	) {

		static SavedSentiment from(ReviewSentiment sentiment) {
			return new SavedSentiment(sentiment.getReviewId(), sentiment.getReviewUpdatedAt(), sentiment.getOverall(),
				sentiment.getCriteriaVersion(), sentiment.getModel(), sentiment.getAspects().stream()
				.collect(Collectors.toMap(ReviewAspectSentiment::getAspect, ReviewAspectSentiment::getSentiment)));
		}
	}

	private ResultActions getSummary(Long sellerId, Long productId) throws Exception {
		return mockMvc.perform(get(SUMMARY_URL, productId)
			.header(HttpHeaders.AUTHORIZATION, tokens.bearer(sellerId, "SELLER")));
	}

	private long writeReview(long orderId, String content) throws Exception {
		orderApiClient.add(BUYER_ID, productId, purchase(orderId));
		String body = mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"orderItemId": %d, "rating": 9, "content": "%s", "mediaUrls": []}
					""".formatted(orderItemId(orderId), content)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number)JsonPath.read(body, "$.reviewId")).longValue();
	}

	private ReviewAnalysisStatus statusOf(long reviewId) {
		return analysisRequestRepository.findAll().stream()
			.filter(request -> request.getReviewId().equals(reviewId))
			.findFirst().orElseThrow()
			.getStatus();
	}

	private void clearReviews() {
		reviewSentimentRepository.deleteAll();
		analysisRequestRepository.deleteAll();
		productReviewRepository.deleteAll();
	}

	private Product product(Long sellerId) {
		Product product = Product.create(sellerId, "청송 꿀사과", "<p>설명</p>", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 10)), List.of("product/a.jpg"));
		product.changeVisibility(ProductVisibility.VISIBLE);
		return product;
	}

	private ReviewablePurchase purchase(long orderId) {
		return new ReviewablePurchase(orderId, orderItemId(orderId), optionId);
	}

	// 테스트에서는 주문마다 주문상품이 하나라 주문 ID로 주문상품 ID를 정한다
	private long orderItemId(long orderId) {
		return orderId * 10;
	}
}
