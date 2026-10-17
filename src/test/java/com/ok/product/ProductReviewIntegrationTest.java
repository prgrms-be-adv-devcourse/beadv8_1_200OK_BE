package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import com.ok.product.review.repository.ProductReviewRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

class ProductReviewIntegrationTest extends AbstractIntegrationTest {

	private static final String BUCKET_URL = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/";
	private static final Long BUYER_ID = 1L;
	private static final Long OTHER_BUYER_ID = 2L;
	private static final long ORDER_ID = 100L;

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	@Autowired
	ProductRepository productRepository;

	@Autowired
	ProductReviewRepository productReviewRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	private MockMvc mockMvc;
	private Long productId;
	private Long optionId;

	@BeforeEach
	void setUp() {
		// @CurrentMemberId가 토큰을 읽으려면 보안 필터가 적용돼야 한다
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		productReviewRepository.deleteAll();

		Product product = Product.create(10L, "청송 꿀사과", "<p>설명</p>", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 10)), List.of("product/a.jpg"));
		product.changeVisibility(ProductVisibility.VISIBLE);
		productId = productRepository.save(product).getId();
		optionId = transactionTemplate.execute(
			status -> productRepository.findById(productId).orElseThrow().getOptions().getFirst().getId());
	}

	// 다른 상품 통합 테스트가 상품을 지울 때 리뷰의 외래 키에 걸리지 않게 리뷰를 남기지 않는다
	@AfterEach
	void tearDown() {
		productReviewRepository.deleteAll();
	}

	@Test
	void 작성_수정_삭제에_따라_상품의_리뷰_수와_평균_별점이_바뀐다() throws Exception {
		// given
		Integer version = productRepository.findById(productId).orElseThrow().getVersion();

		// when: 4.5점 작성
		String body = mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(ORDER_ID, 9, BUCKET_URL + "product_review/a.jpg")))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		long reviewId = ((Number)JsonPath.read(body, "$.reviewId")).longValue();

		// then
		mockMvc.perform(get("/api/v1/products/{productId}", productId))
			.andExpect(jsonPath("$.reviewCount").value(1))
			.andExpect(jsonPath("$.averageRating").value(4.5));

		// when: 3점으로 수정
		mockMvc.perform(put("/api/v1/reviews/{reviewId}", reviewId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"rating": 6, "content": "보통이에요", "mediaUrls": []}
					"""))
			.andExpect(status().isNoContent());

		// then
		mockMvc.perform(get("/api/v1/products/{productId}", productId))
			.andExpect(jsonPath("$.reviewCount").value(1))
			.andExpect(jsonPath("$.averageRating").value(3.0));

		// when: 삭제
		mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
			.andExpect(status().isNoContent());

		// then
		mockMvc.perform(get("/api/v1/products/{productId}", productId))
			.andExpect(jsonPath("$.reviewCount").value(0))
			.andExpect(jsonPath("$.averageRating").value(0.0));
		// 집계는 bulk UPDATE라 판매자의 상품 수정과 충돌하지 않도록 version을 올리지 않는다
		assertThat(productRepository.findById(productId).orElseThrow().getVersion()).isEqualTo(version);
	}

	@Test
	void 리뷰가_달리기_전에_읽어_둔_상품을_저장해도_리뷰_집계를_덮어쓰지_않는다() throws Exception {
		// given: 판매자가 리뷰가 없을 때 상품을 읽어 둔다
		Product staleProduct = productRepository.findById(productId).orElseThrow();
		writeReview(BUYER_ID, ORDER_ID);

		// when: 읽어 둔 상품을 수정해 저장한다
		staleProduct.changeVisibility(ProductVisibility.HIDDEN);
		productRepository.save(staleProduct);

		// then
		Product sut = productRepository.findById(productId).orElseThrow();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getVisibility()).isEqualTo(ProductVisibility.HIDDEN);
			softly.assertThat(sut.getReviewCount()).isEqualTo(1);
			softly.assertThat(sut.getRatingSum()).isEqualTo(9);
		});
	}

	@Test
	void 같은_상품에_동시에_작성해도_모두_저장되고_집계에_빠짐없이_더해진다() throws Exception {
		// given: 서로 다른 주문으로 같은 상품에 동시에 작성한다
		int requests = 2;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();

		// when
		try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				long orderId = ORDER_ID + i;
				results.add(executor.submit(() -> {
					start.await();
					return mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
							.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
							.contentType(MediaType.APPLICATION_JSON)
							.content(createBody(orderId, 9)))
						.andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
		}

		// then: 상품 행을 먼저 잠가 데드락 없이 차례로 저장된다
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		assertThat(statuses).containsOnly(201);
		Product sut = productRepository.findById(productId).orElseThrow();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getReviewCount()).isEqualTo(2);
			softly.assertThat(sut.getRatingSum()).isEqualTo(18);
		});
	}

	@Test
	void 같은_리뷰를_동시에_삭제하면_집계에서_한_번만_빠진다() throws Exception {
		// given
		long reviewId = writeReview(BUYER_ID, ORDER_ID);
		int requests = 2;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();

		// when
		try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId)
							.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
						.andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
		}

		// then: 먼저 잠근 요청만 지우고, 기다린 요청은 이미 지워진 리뷰라 404다
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		assertThat(statuses).containsExactlyInAnyOrder(204, 404);
		Product sut = productRepository.findById(productId).orElseThrow();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getReviewCount()).isZero();
			softly.assertThat(sut.getRatingSum()).isZero();
		});
	}

	@Test
	void 상품별_목록과_내_리뷰에_옵션_이름과_첨부가_나온다() throws Exception {
		// given
		writeReview(BUYER_ID, ORDER_ID, BUCKET_URL + "product_review/a.jpg", BUCKET_URL + "product_review/b.mp4");
		writeReview(OTHER_BUYER_ID, 200L);

		// when & then: 최신순
		mockMvc.perform(get("/api/v1/products/{productId}/reviews", productId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[*].memberId").value(contains(OTHER_BUYER_ID.intValue(), BUYER_ID.intValue())))
			.andExpect(jsonPath("$.content[1].productId").value(productId))
			.andExpect(jsonPath("$.content[1].productOptionId").value(optionId))
			.andExpect(jsonPath("$.content[1].optionName").value("3kg"))
			.andExpect(jsonPath("$.content[1].rating").value(9))
			.andExpect(jsonPath("$.content[1].content").value("달고 맛있어요"))
			.andExpect(jsonPath("$.content[1].media[*].type").value(contains("IMAGE", "VIDEO")))
			.andExpect(jsonPath("$.content[1].media[0].url").value(BUCKET_URL + "product_review/a.jpg"))
			.andExpect(jsonPath("$.content[1].createdAt").exists())
			.andExpect(jsonPath("$.content[1].updatedAt").exists());

		mockMvc.perform(get("/api/v1/reviews/me")
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].memberId").value(BUYER_ID));

		mockMvc.perform(get("/api/v1/products/{productId}/reviews/me", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].memberId").value(BUYER_ID));
	}

	@Test
	void 같은_주문의_같은_상품은_삭제_후에도_다시_쓸_수_없고_다른_주문이면_쓸_수_있다() throws Exception {
		// given
		long reviewId = writeReview(BUYER_ID, ORDER_ID);
		mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId)
			.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")));

		// when & then
		mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(ORDER_ID, 9)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.type", endsWith("/product/duplicate-review")));
		mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(ORDER_ID + 1, 9)))
			.andExpect(status().isCreated());
	}

	@Test
	void 남의_리뷰는_수정하거나_삭제할_수_없다() throws Exception {
		// given
		long reviewId = writeReview(BUYER_ID, ORDER_ID);

		// when & then
		mockMvc.perform(put("/api/v1/reviews/{reviewId}", reviewId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(OTHER_BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"rating": 1, "content": "별로", "mediaUrls": []}
					"""))
			.andExpect(status().isForbidden());
		mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(OTHER_BUYER_ID, "BUYER")))
			.andExpect(status().isForbidden());
	}

	// @Min·@Max가 Spec 인터페이스에만 있어도 검증돼야 한다. 안 되면 PageRequest.of가 예외를 던져 500이 된다
	@ParameterizedTest
	@MethodSource("범위를_벗어난_페이지_요청")
	void 페이지_번호나_크기가_범위를_벗어나면_400(String page, String size) throws Exception {
		mockMvc.perform(get("/api/v1/products/{productId}/reviews", productId).param("page", page).param("size", size))
			.andExpect(status().isBadRequest());
	}

	static Stream<Arguments> 범위를_벗어난_페이지_요청() {
		return Stream.of(
			Arguments.of("-1", "20"),
			Arguments.of("0", "0"),
			Arguments.of("0", "101"));
	}

	@Test
	void 토큰_없이_작성하면_401() throws Exception {
		mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(ORDER_ID, 9)))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void 별점이_범위를_벗어나거나_업로드하지_않은_첨부면_422() throws Exception {
		mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(ORDER_ID, 11)))
			.andExpect(status().isUnprocessableContent());
		mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(ORDER_ID, 9, "https://evil.com/a.jpg")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.type", endsWith("/product/invalid-review-media-url")));
	}

	private long writeReview(Long memberId, long orderId, String... mediaUrls) throws Exception {
		String body = mockMvc.perform(post("/api/v1/products/{productId}/reviews", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(orderId, 9, mediaUrls)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number)JsonPath.read(body, "$.reviewId")).longValue();
	}

	private String createBody(long orderId, int rating, String... mediaUrls) {
		String urls = String.join(",", Arrays.stream(mediaUrls).map(url -> "\"" + url + "\"").toList());
		return """
			{
			  "productOptionId": %d,
			  "orderId": %d,
			  "orderItemId": %d,
			  "rating": %d,
			  "content": "달고 맛있어요",
			  "mediaUrls": [%s]
			}
			""".formatted(optionId, orderId, orderId * 10, rating, urls);
	}
}
