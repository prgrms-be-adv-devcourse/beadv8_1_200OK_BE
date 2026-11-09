package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.ok.product.review.domain.ReviewExpressedKeyword;
import com.ok.product.review.domain.ReviewKeyword;
import com.ok.product.review.domain.Sentiment;
import com.ok.product.review.repository.ProductReviewRepository;
import com.ok.product.review.repository.ReviewAnalysisRequestRepository;
import com.ok.product.review.repository.ReviewKeywordRepository;
import com.ok.product.review.repository.ReviewSentimentRepository;
import com.ok.product.review.service.ReviewKeywordAnalysisFacade;
import com.ok.product.review.service.ReviewKeywordService;
import com.ok.product.review.service.ReviewSentimentAnalysisFacade;
import com.ok.product.review.service.dto.ReviewKeywordAnalysis;
import com.ok.product.review.service.dto.ReviewKeywordTarget;
import com.ok.product.review.service.dto.ReviewablePurchase;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeOrderApiClient;
import com.ok.testsupport.FakeReviewKeywordAnalyzer;
import com.ok.testsupport.FakeReviewSentimentAnalyzer;
import com.ok.testsupport.TestAccessTokens;

// 작업자 스케줄러는 설정이 없어 꺼져 있다. 테스트가 작업자 진입점(analyzePending)을 직접 부른다.
// 키워드 집계와 리뷰 목록은 공개 API라 토큰 없이 부른다.
// 상품은 과일이라 C01(맛있어요)·C02(달아요)·C22(아삭해요)·C25(식감이 좋아요)를 모두 묻는다. 쌀·잡곡은 C02·C22를 묻지 않는다
class ReviewKeywordIntegrationTest extends AbstractIntegrationTest {

	private static final Long SELLER_ID = 10L;
	private static final Long BUYER_ID = 1L;
	private static final String KEYWORDS_URL = "/api/v1/products/{productId}/review-keywords";
	private static final String REVIEWS_URL = "/api/v1/products/{productId}/reviews";
	private static final double EXPRESSED = 0.95;

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
	ReviewKeywordService reviewKeywordService;

	@Autowired
	ReviewSentimentAnalysisFacade reviewSentimentAnalysisFacade;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	FakeOrderApiClient orderApiClient;

	@Autowired
	FakeReviewKeywordAnalyzer analyzer;

	@Autowired
	FakeReviewSentimentAnalyzer sentimentAnalyzer;

	private MockMvc mockMvc;
	private Long productId;
	private Long optionId;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		clearReviews();
		productId = productRepository.save(product()).getId();
		optionId = transactionTemplate.execute(
			status -> productRepository.findById(productId).orElseThrow().getOptions().getFirst().getId());
	}

	// 다른 상품 통합 테스트가 상품을 지울 때 리뷰의 외래 키에 걸리지 않게 리뷰와 분석 결과를 남기지 않는다
	@AfterEach
	void tearDown() {
		clearReviews();
		orderApiClient.clear();
		analyzer.clear();
		sentimentAnalyzer.clear();
	}

	@Test
	void 리뷰를_등록하면_감성과_키워드_분석_요청이_함께_대기로_생긴다() throws Exception {
		// when
		long reviewId = writeReview(100L, "달고 맛있어요");

		// then
		List<ReviewAnalysisRequest> sut = analysisRequestRepository.findAll();
		assertThat(sut).extracting(ReviewAnalysisRequest::getAnalysisType)
			.containsExactlyInAnyOrder(ReviewAnalysisType.SENTIMENT, ReviewAnalysisType.KEYWORD);
		assertSoftly(softly -> sut.forEach(request -> {
			softly.assertThat(request.getReviewId()).isEqualTo(reviewId);
			softly.assertThat(request.getStatus()).isEqualTo(ReviewAnalysisStatus.PENDING);
		}));
	}

	@Test
	void 본문을_수정하면_끝난_감성과_키워드_요청이_모두_대기로_돌아간다() throws Exception {
		// given
		sentimentAnalyzer.respond("달고 맛있어요", Sentiment.POSITIVE, Map.of(ReviewAspect.TASTE, AspectSentiment.POSITIVE));
		analyzer.respond("달고 맛있어요", Map.of("C01", EXPRESSED));
		long reviewId = writeReview(100L, "달고 맛있어요");
		reviewSentimentAnalysisFacade.analyzePending();
		reviewKeywordAnalysisFacade.analyzePending();

		// when
		editReview(reviewId, 9, "맛이 싱거워요");

		// then
		assertSoftly(softly -> {
			softly.assertThat(requestOf(reviewId, ReviewAnalysisType.SENTIMENT).getStatus()).isEqualTo(ReviewAnalysisStatus.PENDING);
			softly.assertThat(requestOf(reviewId, ReviewAnalysisType.KEYWORD).getStatus()).isEqualTo(ReviewAnalysisStatus.PENDING);
		});
	}

	@Test
	void 작업자를_실행하면_확률_0_9_이상인_키워드만_표현한_키워드로_저장된다() throws Exception {
		// given
		analyzer.respond("달고 향긋하고 맛있어요", Map.of("C01", EXPRESSED, "C02", 0.9, "C03", 0.89));
		long reviewId = writeReview(100L, "달고 향긋하고 맛있어요");

		// when
		reviewKeywordAnalysisFacade.analyzePending();

		// then
		LocalDateTime reviewUpdatedAt = productReviewRepository.findById(reviewId).orElseThrow().getUpdatedAt();
		SavedKeyword sut = savedKeyword(reviewId);
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(requestOf(reviewId, ReviewAnalysisType.KEYWORD).getStatus()).isEqualTo(ReviewAnalysisStatus.COMPLETED);
			softly.assertThat(sut.reviewUpdatedAt()).isEqualTo(reviewUpdatedAt);
			// 목록 버전은 product 리소스의 keywords.json(v0.3)에서 읽은 값이다
			softly.assertThat(sut.keywordVersion()).isEqualTo("v0.3");
			softly.assertThat(sut.model()).isEqualTo(FakeReviewKeywordAnalyzer.MODEL);
			softly.assertThat(sut.expressedKeywordIds()).containsExactlyInAnyOrder("C01", "C02");
		});
	}

	@Test
	void 구체적인_식감_키워드를_표현하면_식감이_좋아요는_빼고_아니면_남긴다() throws Exception {
		// given
		analyzer.respond("아삭하고 식감이 좋아요", Map.of("C22", EXPRESSED, "C25", EXPRESSED));
		analyzer.respond("식감이 좋아요", Map.of("C25", EXPRESSED));
		long withTextureId = writeReview(100L, "아삭하고 식감이 좋아요");
		long generalOnlyId = writeReview(101L, "식감이 좋아요");

		// when
		reviewKeywordAnalysisFacade.analyzePending();

		// then
		assertSoftly(softly -> {
			softly.assertThat(savedKeyword(withTextureId).expressedKeywordIds()).containsExactly("C22");
			softly.assertThat(savedKeyword(generalOnlyId).expressedKeywordIds()).containsExactly("C25");
		});
	}

	@Test
	void 로그인_없이_리뷰_3개_이상이_표현한_키워드를_많은_순_같으면_ID_순으로_조회한다() throws Exception {
		// given: 12개 리뷰 모두 C01, 5개씩 C02·C03, 2개만 C05
		for (int i = 0; i < 12; i++) {
			Map<String, Double> probabilities = new HashMap<>(Map.of("C01", EXPRESSED));
			if (i < 5) {
				probabilities.put("C03", EXPRESSED);
			} else if (i < 10) {
				probabilities.put("C02", EXPRESSED);
			} else {
				probabilities.put("C05", EXPRESSED);
			}
			writeAnalyzableReview(100L + i, probabilities);
		}
		reviewKeywordAnalysisFacade.analyzePending();

		// when
		ResultActions sut = getKeywords(productId);

		// then
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzedReviewCount").value(12))
			.andExpect(jsonPath("$.keywords[*].keywordId").value(contains("C01", "C02", "C03")))
			.andExpect(jsonPath("$.keywords[*].reviewCount").value(contains(12, 5, 5)))
			.andExpect(jsonPath("$.keywords[1].phrase").value("달아요"))
			.andExpect(jsonPath("$.keywords[1].aspect").value("TASTE"));
	}

	@Test
	void 키워드는_최대_10개까지만_내린다() throws Exception {
		// given: 10개 리뷰가 모두 같은 11개 키워드를 표현해 표현한 리뷰 수가 같다
		List<String> keywordIds = List.of("C01", "C02", "C03", "C05", "C06", "C07", "C08", "C09", "C10", "C12", "C13");
		Map<String, Double> probabilities = keywordIds.stream().collect(Collectors.toMap(id -> id, id -> EXPRESSED));
		for (int i = 0; i < 10; i++) {
			writeAnalyzableReview(100L + i, probabilities);
		}
		reviewKeywordAnalysisFacade.analyzePending();

		// when & then
		getKeywords(productId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keywords[*].keywordId").value(contains(keywordIds.subList(0, 10).toArray())));
	}

	@Test
	void 분석_완료_리뷰가_10개_미만이면_키워드는_비고_분석_완료_리뷰_수는_내린다() throws Exception {
		// given
		for (int i = 0; i < 9; i++) {
			writeAnalyzableReview(100L + i, Map.of("C01", EXPRESSED));
		}
		reviewKeywordAnalysisFacade.analyzePending();

		// when & then
		getKeywords(productId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzedReviewCount").value(9))
			.andExpect(jsonPath("$.keywords").value(empty()));
	}

	@Test
	void 상품의_현재_분류에_묻지_않는_키워드는_세지_않는다() throws Exception {
		// given: 과일일 때 C01·C22를 표현했는데 상품 분류가 쌀·잡곡으로 바뀐다
		for (int i = 0; i < 10; i++) {
			writeAnalyzableReview(100L + i, Map.of("C01", EXPRESSED, "C22", EXPRESSED));
		}
		reviewKeywordAnalysisFacade.analyzePending();
		jdbcTemplate.update("UPDATE " + ProductSchema.NAME + ".product SET category = ? WHERE id = ?",
			ProductCategory.GRAIN.name(), productId);

		// when & then
		getKeywords(productId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzedReviewCount").value(10))
			.andExpect(jsonPath("$.keywords[*].keywordId").value(contains("C01")));
	}

	@Test
	void 수정_뒤_재분석_전인_리뷰는_분석_완료_리뷰_수와_표현한_리뷰_수에서_빠진다() throws Exception {
		// given: 11개 리뷰 모두 C01, 3개가 C02
		List<Long> reviewIds = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			reviewIds.add(writeAnalyzableReview(100L + i,
				i < 3 ? Map.of("C01", EXPRESSED, "C02", EXPRESSED) : Map.of("C01", EXPRESSED)));
		}
		reviewKeywordAnalysisFacade.analyzePending();

		// when
		editReview(reviewIds.getFirst(), 9, "다시 먹어 보니 싱거워요");

		// then
		getKeywords(productId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzedReviewCount").value(10))
			.andExpect(jsonPath("$.keywords[*].keywordId").value(contains("C01")))
			.andExpect(jsonPath("$.keywords[*].reviewCount").value(contains(10)));
	}

	@Test
	void 수정한_리뷰를_다시_분석하면_결과_한_행의_표현한_키워드가_바뀐다() throws Exception {
		// given: 수정 뒤에는 C01이 빠지고 C02는 남고 C03이 더해진다
		analyzer.respond("맛있고 달아요", Map.of("C01", EXPRESSED, "C02", EXPRESSED));
		analyzer.respond("달고 향이 좋아요", Map.of("C02", EXPRESSED, "C03", EXPRESSED));
		long reviewId = writeReview(100L, "맛있고 달아요");
		reviewKeywordAnalysisFacade.analyzePending();
		editReview(reviewId, 9, "달고 향이 좋아요");

		// when
		reviewKeywordAnalysisFacade.analyzePending();

		// then
		assertSoftly(softly -> {
			softly.assertThat(requestOf(reviewId, ReviewAnalysisType.KEYWORD).getStatus()).isEqualTo(ReviewAnalysisStatus.COMPLETED);
			softly.assertThat(reviewKeywordRepository.count()).isOne();
			softly.assertThat(savedKeyword(reviewId).expressedKeywordIds()).containsExactlyInAnyOrder("C02", "C03");
		});
	}

	@Test
	void 분석하는_동안_리뷰가_수정되면_결과를_저장하지_않고_요청을_대기로_둔다() throws Exception {
		// given
		analyzer.respond("달고 맛있어요", Map.of("C01", EXPRESSED));
		long reviewId = writeReview(100L, "달고 맛있어요");
		ReviewKeywordTarget target = reviewKeywordService.findPendingTargets(20).getFirst();
		ReviewKeywordAnalysis analysis = analyzer.analyze(target.input());
		editReview(reviewId, 9, "맛이 싱거워요");

		// when
		reviewKeywordService.complete(target, analysis);

		// then
		assertSoftly(softly -> {
			softly.assertThat(reviewKeywordRepository.count()).isZero();
			softly.assertThat(requestOf(reviewId, ReviewAnalysisType.KEYWORD).getStatus()).isEqualTo(ReviewAnalysisStatus.PENDING);
		});
	}

	@Test
	void 대기_중에_삭제한_리뷰의_요청은_분석기_호출_없이_취소된다() throws Exception {
		// given
		long reviewId = writeReview(100L, "달고 맛있어요");
		deleteReview(reviewId);

		// when
		reviewKeywordAnalysisFacade.analyzePending();

		// then
		assertSoftly(softly -> {
			softly.assertThat(requestOf(reviewId, ReviewAnalysisType.KEYWORD).getStatus()).isEqualTo(ReviewAnalysisStatus.CANCELLED);
			softly.assertThat(analyzer.analyzedContents()).isEmpty();
		});
	}

	@Test
	void 내용_없는_리뷰는_분석기를_부르지_않고_분석_안_함으로_끝난다() throws Exception {
		// given
		long reviewId = writeReview(100L, "ㅋㅋㅋ");

		// when
		reviewKeywordAnalysisFacade.analyzePending();

		// then
		assertSoftly(softly -> {
			softly.assertThat(requestOf(reviewId, ReviewAnalysisType.KEYWORD).getStatus()).isEqualTo(ReviewAnalysisStatus.SKIPPED);
			softly.assertThat(analyzer.analyzedContents()).isEmpty();
			softly.assertThat(reviewKeywordRepository.count()).isZero();
		});
	}

	@Test
	void 상품_행이_없는_리뷰의_요청은_취소되고_다른_요청은_분석된다() throws Exception {
		// given
		analyzer.respond("신선해요", Map.of("C05", EXPRESSED));
		long orphanReviewId = writeReview(100L, "달고 맛있어요");
		long reviewId = writeReview(101L, "신선해요");
		// 운영 DB에서 외래키 검사를 끄고 상품 행을 지운 상황. 리뷰가 없는 상품을 가리키게 해 같은 상태를 만든다.
		// 같은 커넥션에서 실행하려고 트랜잭션으로 묶는다
		transactionTemplate.executeWithoutResult(status -> {
			jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
			jdbcTemplate.update("UPDATE " + ProductSchema.NAME + ".product_review SET product_id = ? WHERE id = ?",
				productId + 1_000, orphanReviewId);
			jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
		});

		// when
		reviewKeywordAnalysisFacade.analyzePending();

		// then
		assertSoftly(softly -> {
			softly.assertThat(requestOf(orphanReviewId, ReviewAnalysisType.KEYWORD).getStatus())
				.isEqualTo(ReviewAnalysisStatus.CANCELLED);
			softly.assertThat(requestOf(reviewId, ReviewAnalysisType.KEYWORD).getStatus())
				.isEqualTo(ReviewAnalysisStatus.COMPLETED);
			softly.assertThat(analyzer.analyzedContents()).containsExactly("신선해요");
		});
	}

	@Test
	void 키워드_분석이_최종_실패해도_감성_요청과_결과는_그대로다() throws Exception {
		// given
		sentimentAnalyzer.respond("달고 맛있어요", Sentiment.POSITIVE, Map.of(ReviewAspect.TASTE, AspectSentiment.POSITIVE));
		analyzer.fail("달고 맛있어요");
		long reviewId = writeReview(100L, "달고 맛있어요");

		// when
		reviewKeywordAnalysisFacade.analyzePending();
		reviewSentimentAnalysisFacade.analyzePending();

		// then
		ReviewAnalysisRequest keywordRequest = requestOf(reviewId, ReviewAnalysisType.KEYWORD);
		assertSoftly(softly -> {
			softly.assertThat(keywordRequest.getStatus()).isEqualTo(ReviewAnalysisStatus.FAILED);
			softly.assertThat(keywordRequest.getLastError()).contains("Fake 키워드 분석 실패");
			softly.assertThat(requestOf(reviewId, ReviewAnalysisType.SENTIMENT).getStatus())
				.isEqualTo(ReviewAnalysisStatus.COMPLETED);
			softly.assertThat(reviewSentimentRepository.findByReviewId(reviewId)).isPresent();
			softly.assertThat(reviewKeywordRepository.count()).isZero();
		});
	}

	@Test
	void 키워드로_거른_리뷰_목록은_칩의_리뷰_수와_같은_리뷰를_최신순으로_내린다() throws Exception {
		// given: 12개 리뷰 모두 C01, 앞 5개가 C02. C02 리뷰 하나는 수정 뒤 재분석 전이고 하나는 삭제된다
		List<Long> reviewIds = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			reviewIds.add(writeAnalyzableReview(100L + i,
				i < 5 ? Map.of("C01", EXPRESSED, "C02", EXPRESSED) : Map.of("C01", EXPRESSED)));
		}
		reviewKeywordAnalysisFacade.analyzePending();
		editReview(reviewIds.get(0), 9, "다시 먹어 보니 싱거워요");
		deleteReview(reviewIds.get(1));
		String keywords = getKeywords(productId).andReturn().getResponse().getContentAsString();
		List<Integer> chipReviewCount = JsonPath.read(keywords, "$.keywords[?(@.keywordId == 'C02')].reviewCount");

		// when
		ResultActions sut = mockMvc.perform(get(REVIEWS_URL, productId).param("keyword", "C02").param("size", "2"));

		// then
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(chipReviewCount.getFirst()))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.content[*].reviewId").value(contains(
				reviewIds.get(4).intValue(), reviewIds.get(3).intValue())));
		mockMvc.perform(get(REVIEWS_URL, productId).param("keyword", "C02").param("size", "2").param("page", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].reviewId").value(contains(reviewIds.get(2).intValue())));
	}

	@Test
	void 다른_상품의_분석_완료_리뷰는_칩과_키워드_필터에_섞이지_않는다() throws Exception {
		// given: 이 상품은 10개 리뷰가 C01, 다른 상품은 3개 리뷰가 C01·C02를 표현한다
		for (int i = 0; i < 10; i++) {
			writeAnalyzableReview(100L + i, Map.of("C01", EXPRESSED));
		}
		Long otherProductId = productRepository.save(product()).getId();
		Long otherOptionId = transactionTemplate.execute(
			status -> productRepository.findById(otherProductId).orElseThrow().getOptions().getFirst().getId());
		for (int i = 0; i < 3; i++) {
			writeAnalyzableReview(otherProductId, otherOptionId, 200L + i, Map.of("C01", EXPRESSED, "C02", EXPRESSED));
		}
		reviewKeywordAnalysisFacade.analyzePending();

		// when
		ResultActions sut = getKeywords(productId);

		// then
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.analyzedReviewCount").value(10))
			.andExpect(jsonPath("$.keywords[*].keywordId").value(contains("C01")))
			.andExpect(jsonPath("$.keywords[*].reviewCount").value(contains(10)));
		mockMvc.perform(get(REVIEWS_URL, productId).param("keyword", "C01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(10));
		mockMvc.perform(get(REVIEWS_URL, productId).param("keyword", "C02"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void 칩에_뜨지_않는_키워드로도_리뷰를_거른다() throws Exception {
		// given: 분석 완료 리뷰가 10개 미만이고 C03을 표현한 리뷰는 하나뿐이라 칩이 없다
		long expressedId = writeAnalyzableReview(100L, Map.of("C01", EXPRESSED, "C03", EXPRESSED));
		writeAnalyzableReview(101L, Map.of("C01", EXPRESSED));
		reviewKeywordAnalysisFacade.analyzePending();

		// when
		ResultActions sut = mockMvc.perform(get(REVIEWS_URL, productId).param("keyword", "C03"));

		// then
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[*].reviewId").value(contains((int)expressedId)));
	}

	@Test
	void 상품의_현재_분류에_묻지_않는_키워드로_거르면_빈_페이지다() throws Exception {
		// given: 과일일 때 C22를 표현했는데 상품 분류가 쌀·잡곡으로 바뀐다
		writeAnalyzableReview(100L, Map.of("C22", EXPRESSED));
		reviewKeywordAnalysisFacade.analyzePending();
		jdbcTemplate.update("UPDATE " + ProductSchema.NAME + ".product SET category = ? WHERE id = ?",
			ProductCategory.GRAIN.name(), productId);

		// when & then
		mockMvc.perform(get(REVIEWS_URL, productId).param("keyword", "C22"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0))
			.andExpect(jsonPath("$.content").value(empty()));
	}

	@ParameterizedTest
	@MethodSource("빈_키워드")
	void 빈_키워드로_부르면_거르지_않고_전체_목록을_내린다(String keyword) throws Exception {
		// given: 분석 전 리뷰도 나와야 한다
		long analyzedId = writeAnalyzableReview(100L, Map.of("C01", EXPRESSED));
		reviewKeywordAnalysisFacade.analyzePending();
		long pendingId = writeReview(101L, "아직 분석 전이에요");

		// when
		ResultActions sut = mockMvc.perform(get(REVIEWS_URL, productId).param("keyword", keyword));

		// then
		sut.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[*].reviewId").value(contains((int)pendingId, (int)analyzedId)));
	}

	static Stream<String> 빈_키워드() {
		return Stream.of("", " ");
	}

	@Test
	void 목록에_없는_키워드로_거르면_400이다() throws Exception {
		// when & then
		mockMvc.perform(get(REVIEWS_URL, productId).param("keyword", "Z99"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type", endsWith("/product/review-keyword-not-found")));
	}

	@Test
	void 없는_상품의_리뷰를_키워드로_거르면_404다() throws Exception {
		// when & then
		mockMvc.perform(get(REVIEWS_URL, productId + 1_000).param("keyword", "C02"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type", endsWith("/product/product-not-found")));
	}

	@Test
	void 없는_상품을_조회하면_404다() throws Exception {
		// when & then
		getKeywords(productId + 1_000)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type", endsWith("/product/product-not-found")));
	}

	// 표현한 키워드는 지연 로딩이라 트랜잭션 안에서 꺼내 둔다
	private record SavedKeyword(LocalDateTime reviewUpdatedAt, String keywordVersion, String model, Set<String> expressedKeywordIds) {

		static SavedKeyword from(ReviewKeyword reviewKeyword) {
			return new SavedKeyword(reviewKeyword.getReviewUpdatedAt(), reviewKeyword.getKeywordVersion(), reviewKeyword.getModel(),
				reviewKeyword.getExpressedKeywords().stream().map(ReviewExpressedKeyword::getKeywordId).collect(Collectors.toSet()));
		}
	}

	private SavedKeyword savedKeyword(long reviewId) {
		return transactionTemplate.execute(
			status -> SavedKeyword.from(reviewKeywordRepository.findByReviewId(reviewId).orElseThrow()));
	}

	private ResultActions getKeywords(Long productId) throws Exception {
		return mockMvc.perform(get(KEYWORDS_URL, productId));
	}

	private long writeAnalyzableReview(long orderId, Map<String, Double> probabilities) throws Exception {
		return writeAnalyzableReview(productId, optionId, orderId, probabilities);
	}

	// 본문을 주문 ID로 구분해 리뷰마다 다른 결과를 넣는다
	private long writeAnalyzableReview(Long productId, Long optionId, long orderId, Map<String, Double> probabilities)
		throws Exception {
		String content = "리뷰 " + orderId + " 맛있어요";
		analyzer.respond(content, probabilities);
		return writeReview(productId, optionId, orderId, content);
	}

	private long writeReview(long orderId, String content) throws Exception {
		return writeReview(productId, optionId, orderId, content);
	}

	private long writeReview(Long productId, Long optionId, long orderId, String content) throws Exception {
		orderApiClient.add(BUYER_ID, productId, new ReviewablePurchase(orderId, orderItemId(orderId), optionId));
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

	private void editReview(long reviewId, int rating, String content) throws Exception {
		mockMvc.perform(put("/api/v1/reviews/{reviewId}", reviewId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"rating": %d, "content": "%s", "mediaUrls": []}
					""".formatted(rating, content)))
			.andExpect(status().isNoContent());
	}

	private void deleteReview(long reviewId) throws Exception {
		mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
			.andExpect(status().isNoContent());
	}

	private ReviewAnalysisRequest requestOf(long reviewId, ReviewAnalysisType analysisType) {
		return analysisRequestRepository.findByReviewIdAndAnalysisType(reviewId, analysisType).orElseThrow();
	}

	private void clearReviews() {
		reviewKeywordRepository.deleteAll();
		reviewSentimentRepository.deleteAll();
		analysisRequestRepository.deleteAll();
		productReviewRepository.deleteAll();
	}

	private Product product() {
		Product product = Product.create(SELLER_ID, "청송 꿀사과", "<p>설명</p>", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 10)), List.of("product/a.jpg"));
		product.changeVisibility(ProductVisibility.VISIBLE);
		return product;
	}

	// 테스트에서는 주문마다 주문상품이 하나라 주문 ID로 주문상품 ID를 정한다
	private long orderItemId(long orderId) {
		return orderId * 10;
	}
}
