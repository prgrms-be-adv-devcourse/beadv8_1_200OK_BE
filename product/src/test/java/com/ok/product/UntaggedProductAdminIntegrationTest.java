package com.ok.product;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJudgmentJpaRepository;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

// 판정 행은 작업자를 거치지 않고 정한 상태·시각으로 바로 넣는다
class UntaggedProductAdminIntegrationTest extends AbstractIntegrationTest {

	private static final String URL = "/api/v1/admin/product-search/untagged-products";
	private static final String JUDGMENT_TABLE = ProductSchema.NAME + ".product_tag_judgment";
	private static final String TAG_TABLE = ProductSchema.NAME + ".product_tag";
	private static final Long ADMIN_ID = 99L;

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	@Autowired
	ProductJpaRepository productRepository;

	@Autowired
	ProductTagJpaRepository productTagRepository;

	@Autowired
	ProductTagJudgmentJpaRepository productTagJudgmentRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Clock clock;

	private MockMvc mockMvc;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		deleteAll();
		today = LocalDate.now(clock);
	}

	@AfterEach
	void tearDown() {
		deleteAll();
	}

	@Test
	void 기간_안에_판정을_마치고_태그가_0개인_상품만_최근_판정_순으로_준다() throws Exception {
		// given
		LocalDateTime base = today.minusDays(1).atTime(9, 0);
		Long ginger = product("국산 생강");
		Long garlic = product("의성 마늘");
		Long tagged = product("제주 감귤");
		Long failed = product("청양고추");
		Long pending = product("양파");
		Long outside = product("대파");
		judgment(ginger, "COMPLETED", base, "T10", 0.83);
		judgment(garlic, "COMPLETED", base.plusHours(1), null, null);
		judgment(tagged, "COMPLETED", base.plusHours(2), "T03", 0.95);
		jdbcTemplate.update("INSERT INTO " + TAG_TABLE + " (product_id, tag_id, created_at, updated_at) "
			+ "VALUES (?, 'T03', NOW(6), NOW(6))", tagged);
		judgment(failed, "FAILED", base.plusHours(3), "T10", 0.4);
		judgment(pending, "PENDING", base.plusHours(4), "T05", 0.5);
		judgment(outside, "COMPLETED", today.minusDays(3).atTime(23, 59, 59), "T05", 0.5);

		// when & then
		mockMvc.perform(admin(get(URL).param("from", today.minusDays(2).toString()).param("to", today.toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].productId").value(contains(garlic.intValue(), ginger.intValue())))
			.andExpect(jsonPath("$[1].productName").value("국산 생강"))
			.andExpect(jsonPath("$[1].judgedAt").value(base.toString() + ":00"))
			.andExpect(jsonPath("$[1].topTagLabel").value("김장·김치용"))
			.andExpect(jsonPath("$[1].topScore").value(0.83))
			.andExpect(jsonPath("$[0].topTagLabel").doesNotExist());
	}

	@Test
	void 시작일이_종료일보다_늦으면_400이다() throws Exception {
		// when & then
		mockMvc.perform(admin(get(URL).param("from", today.toString()).param("to", today.minusDays(1).toString())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type", endsWith("/product/invalid-tag-judgment-period")));
	}

	@ParameterizedTest
	@MethodSource("범위_밖_개수")
	void 개수가_1에서_100_밖이면_400이다(String limit) throws Exception {
		// when & then
		mockMvc.perform(admin(get(URL).param("from", today.toString()).param("to", today.toString())
				.param("limit", limit)))
			.andExpect(status().isBadRequest());
	}

	static Stream<String> 범위_밖_개수() {
		return Stream.of("0", "101");
	}

	@ParameterizedTest
	@MethodSource("관리자가_아닌_역할")
	void 관리자가_아니면_403이다(String role) throws Exception {
		// when & then
		mockMvc.perform(get(URL).param("from", today.toString()).param("to", today.toString())
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, role)))
			.andExpect(status().isForbidden());
	}

	static Stream<String> 관리자가_아닌_역할() {
		return Stream.of("BUYER", "SELLER");
	}

	private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN"));
	}

	private Long product(String name) {
		return productRepository.save(Product.create(1L, name, "<p>설명</p>", ProductCategory.VEGETABLE,
			ProductRegion.GYEONGBUK, List.of(ProductOptionSpec.newOption("1kg", 10_000L, 10_000L, 10)),
			List.of("product/a.jpg"))).getId();
	}

	private void judgment(Long productId, String status, LocalDateTime judgedAt, String topTagId, Double topScore) {
		jdbcTemplate.update("INSERT INTO " + JUDGMENT_TABLE + " (product_id, status, tag_version, model, attempt_count, "
				+ "next_attempt_at, requested_at, judged_at, top_tag_id, top_score, created_at, updated_at) "
				+ "VALUES (?, ?, 'v3', 'jev-1.14.0', 0, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
			productId, status, judgedAt, judgedAt, judgedAt, topTagId, topScore);
	}

	// 태그와 판정 행이 상품을 외래키로 가리키므로 먼저 지운다
	private void deleteAll() {
		productTagRepository.deleteAllInBatch();
		productTagJudgmentRepository.deleteAllInBatch();
		productRepository.deleteAll();
	}
}
