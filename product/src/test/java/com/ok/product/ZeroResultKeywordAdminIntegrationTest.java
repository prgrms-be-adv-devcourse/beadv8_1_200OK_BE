package com.ok.product;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.ok.product.product.adapter.out.persistence.ProductSearchLogJpaRepository;
import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.product.product.domain.ProductSearchLog;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

// 로그 행은 검색을 거치지 않고 정한 시각으로 바로 넣는다. 보존 기간은 module-test.yml의 90일이다
class ZeroResultKeywordAdminIntegrationTest extends AbstractIntegrationTest {

	private static final String URL = "/api/v1/admin/product-search/zero-result-keywords";
	private static final String LOG_TABLE = ProductSchema.NAME + ".product_search_log";
	private static final String PROBLEM_BASE = "https://api.example.com/problems/";
	private static final Long ADMIN_ID = 99L;
	private static final String ISO_DATE = "yyyy-MM-dd";

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	@Autowired
	ProductSearchLogJpaRepository productSearchLogRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Clock clock;

	private MockMvc mockMvc;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		jdbcTemplate.execute("TRUNCATE TABLE " + LOG_TABLE);
		today = LocalDate.now(clock);
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("TRUNCATE TABLE " + LOG_TABLE);
	}

	@Test
	void 기간_안의_0건_검색어만_정규화_검색어로_묶어_횟수_다음_최근_검색_순으로_준다() throws Exception {
		// given
		LocalDateTime base = today.minusDays(1).atTime(9, 0, 30);
		save(
			log("제사상", 0, base),
			log("제사상", 0, base.plusMinutes(1)),
			log("제사상", 0, base.plusMinutes(2)),
			log("샤인 머스켓", 0, base.plusMinutes(3)),
			log("샤인머스켓", 0, base.plusMinutes(4)),
			log("수험생간식", 0, base.plusMinutes(5)),
			log("곶감", 0, base.plusMinutes(6)));
		for (int i = 0; i < 5; i++) {
			save(log("사과", 3, base.plusMinutes(i)));
		}

		// when & then
		mockMvc.perform(admin(get(URL).param("from", today.minusDays(1).toString()).param("to", today.toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].normalizedKeyword").value(contains("제사상", "샤인머스켓", "곶감", "수험생간식")))
			.andExpect(jsonPath("$[*].zeroResultCount").value(contains(3, 2, 1, 1)))
			.andExpect(jsonPath("$[1].sampleKeyword").value("샤인머스켓"))
			.andExpect(jsonPath("$[1].lastSearchedAt").value(base.plusMinutes(4).toString()))
			.andExpect(jsonPath("$[0].sampleKeyword").value("제사상"));
	}

	@Test
	void 시작일과_종료일은_하루_전체가_들어가고_종료일_다음_날은_빠진다() throws Exception {
		// given
		LocalDate from = today.minusDays(3);
		LocalDate to = today.minusDays(2);
		save(
			log("제사상", 0, from.minusDays(1).atTime(LocalTime.MAX)),
			log("제사상", 0, from.atStartOfDay()),
			log("제사상", 0, to.atTime(LocalTime.MAX)),
			log("제사상", 0, to.plusDays(1).atStartOfDay()));

		// when & then
		mockMvc.perform(admin(get(URL).param("from", from.toString()).param("to", to.toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].zeroResultCount").value(2));
	}

	@Test
	void 기간_안에_0건_검색이_없으면_빈_목록이다() throws Exception {
		// given
		save(log("사과", 3, today.atStartOfDay()));

		// when & then
		mockMvc.perform(admin(get(URL).param("from", today.toString()).param("to", today.toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void 개수는_보존_기간_첫날부터_100까지_받는다() throws Exception {
		// given
		LocalDate oldest = today.minusDays(90);

		// when & then
		mockMvc.perform(admin(get(URL).param("from", oldest.toString()).param("to", today.toString()).param("limit", "100")))
			.andExpect(status().isOk());
	}

	@ParameterizedTest
	@MethodSource("잘못된_조회_조건")
	void 잘못된_기간이나_개수는_400_ProblemDetail이다(Integer fromDaysAgo, String fromPattern, int toDaysAgo, String limit,
		String type) throws Exception {
		// given
		MockHttpServletRequestBuilder request = get(URL)
			.param("to", today.minusDays(toDaysAgo).toString())
			.param("limit", limit);
		if (fromDaysAgo != null) {
			request.param("from", today.minusDays(fromDaysAgo).format(DateTimeFormatter.ofPattern(fromPattern)));
		}

		// when & then
		mockMvc.perform(admin(request))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value(PROBLEM_BASE + type));
	}

	// 기간 규칙은 서비스의 에러 코드, 날짜 형식은 타입 변환 오류, 빠진 값과 개수 범위는 요청 검증 오류(bad-request)로 간다
	static Stream<Arguments> 잘못된_조회_조건() {
		return Stream.of(
			Arguments.of(0, ISO_DATE, 1, "20", "product/invalid-search-log-period"),
			Arguments.of(91, ISO_DATE, 0, "20", "product/search-log-period-beyond-retention"),
			Arguments.of(0, "yyyy/MM/dd", 0, "20", "invalid-query-parameter"),
			Arguments.of(null, null, 0, "20", "bad-request"),
			Arguments.of(1, ISO_DATE, 0, "0", "bad-request"),
			Arguments.of(1, ISO_DATE, 0, "101", "bad-request"));
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

	@Test
	void 로그인하지_않으면_401이다() throws Exception {
		// when & then
		mockMvc.perform(get(URL).param("from", today.toString()).param("to", today.toString()))
			.andExpect(status().isUnauthorized());
	}

	private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN"));
	}

	private void save(ProductSearchLog... logs) {
		productSearchLogRepository.saveAll(List.of(logs));
	}

	private static ProductSearchLog log(String keyword, long resultCount, LocalDateTime searchedAt) {
		return ProductSearchLog.of(keyword, ProductSearchKeyword.from(keyword).orElseThrow(), resultCount, 10, false,
			searchedAt);
	}
}
