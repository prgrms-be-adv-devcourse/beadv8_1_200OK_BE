package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJudgmentJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductSearchLogJpaRepository;
import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ProductCreateCommand;
import com.ok.product.product.application.dto.ProductOptionCommand;
import com.ok.product.product.application.dto.ProductRegionCode;
import com.ok.product.product.application.dto.ProductStatusCode;
import com.ok.product.product.application.dto.ProductVisibilityCode;
import com.ok.product.product.application.port.in.ProductSearchLogRetentionUseCase;
import com.ok.product.product.application.service.ProductService;
import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.product.product.domain.ProductSearchLog;
import com.ok.testsupport.AbstractIntegrationTest;

// 로그는 전용 실행기에서 비동기로 저장되므로 행이 생길 때까지 제한 시간 안에서 다시 확인한다.
// module-test.yml에서 실행기 스레드를 하나로 두어, 나중 검색의 행이 보이면 앞선 검색의 저장도 끝난 것이다
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ProductSearchLogIntegrationTest extends AbstractIntegrationTest {

	private static final String IMAGE = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg";
	private static final String SEARCH_TABLE = ProductSchema.NAME + ".product_search";
	private static final String LOG_TABLE = ProductSchema.NAME + ".product_search_log";
	private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductService productService;

	@Autowired
	ProductJpaRepository productRepository;

	@Autowired
	ProductTagJpaRepository productTagRepository;

	@Autowired
	ProductTagJudgmentJpaRepository productTagJudgmentRepository;

	@Autowired
	ProductSearchLogJpaRepository productSearchLogRepository;

	@Autowired
	ProductSearchLogRetentionUseCase productSearchLogRetentionUseCase;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Clock clock;

	// FULLTEXT 점수는 검색어가 든 상품이 전체 중 일부일 때만 0보다 크므로, 검색어와 겹치지 않는 상품을 함께 둔다
	@BeforeEach
	void setUp() {
		deleteAll();
		create("제주 당근 3kg");
		create("남해 시금치 1kg");
		create("양구 시래기 500g");
		create("장흥 표고 1kg");
	}

	@AfterEach
	void tearDown() {
		deleteAll();
	}

	@Test
	void 키워드_검색_첫_페이지는_응답과_같은_결과_수와_제안_여부로_로그_한_행을_남긴다() throws Exception {
		// given
		create("[김천] 샤인머스캣 2kg");
		create("[상주] 샤인머스캣 1kg");
		create("[영천] 샤인머스캣 4kg");
		LocalDateTime before = LocalDateTime.now(clock);

		// when
		mockMvc.perform(get("/api/v1/products").param("keyword", "샤인 머스캣").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.suggestion").doesNotExist());
		mockMvc.perform(get("/api/v1/products").param("keyword", "고추가루"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0))
			.andExpect(jsonPath("$.suggestion").value("고춧가루"));

		// then
		awaitUntil(() -> logsOf("고추가루").size() == 1);
		List<ProductSearchLog> shineMuscat = logsOf("샤인 머스캣");
		assertThat(shineMuscat).hasSize(1);
		ProductSearchLog sut = shineMuscat.getFirst();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getNormalizedKeyword()).isEqualTo("샤인머스캣");
			softly.assertThat(sut.getResultCount()).isEqualTo(3);
			softly.assertThat(sut.getLatencyMillis()).isNotNegative();
			softly.assertThat(sut.isSuggested()).isFalse();
			softly.assertThat(sut.getSearchedAt()).isAfterOrEqualTo(before);
		});
		ProductSearchLog typo = logsOf("고추가루").getFirst();
		assertThat(typo).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(typo.getNormalizedKeyword()).isEqualTo("고추가루");
			softly.assertThat(typo.getResultCount()).isZero();
			softly.assertThat(typo.isSuggested()).isTrue();
		});
	}

	// 테이블 이름을 잠시 바꿔 저장 SQL이 실패하게 한다. 실패 WARN이 보일 때까지 기다린 뒤 되돌려야
	// 저장 작업이 되돌린 테이블에 성공하는 일이 없다
	@Test
	void 로그_저장이_실패해도_검색_응답은_정상이고_검색어_원문_없이_WARN만_남는다(CapturedOutput output) throws Exception {
		// given
		create("청송 사과 5kg");
		String keyword = "청송사과선물세트";
		jdbcTemplate.execute("RENAME TABLE " + LOG_TABLE + " TO " + LOG_TABLE + "_unavailable");
		try {
			// when
			mockMvc.perform(get("/api/v1/products").param("keyword", keyword))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));

			// then
			awaitUntil(() -> output.getOut().contains("검색어 로그를 저장하지 못해 버렸습니다"));
		} finally {
			jdbcTemplate.execute("RENAME TABLE " + LOG_TABLE + "_unavailable TO " + LOG_TABLE);
		}
		assertThat(output.getOut()).doesNotContain(keyword);
		assertThat(logsOf(keyword)).isEmpty();
	}

	@Test
	void 보존_기간이_지난_날의_로그만_지우고_다시_돌려도_같다() {
		// given
		LocalDateTime now = LocalDateTime.now(clock);
		productSearchLogRepository.saveAll(List.of(
			log("91일 전", now.minusDays(91)),
			log("90일 전", now.minusDays(90)),
			log("어제", now.minusDays(1))));

		// when
		productSearchLogRetentionUseCase.deleteExpiredLogs();
		List<String> afterFirst = keywords();
		productSearchLogRetentionUseCase.deleteExpiredLogs();

		// then
		assertThat(afterFirst).containsExactlyInAnyOrder("90일 전", "어제");
		assertThat(keywords()).containsExactlyInAnyOrder("90일 전", "어제");
	}

	private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("제한 시간 안에 조건을 만족하지 않았다").isLessThan(deadline);
			Thread.sleep(20);
		}
	}

	private List<ProductSearchLog> logsOf(String keyword) {
		return productSearchLogRepository.findAll().stream()
			.filter(log -> log.getKeyword().equals(keyword))
			.toList();
	}

	private List<String> keywords() {
		return productSearchLogRepository.findAll().stream().map(ProductSearchLog::getKeyword).toList();
	}

	private ProductSearchLog log(String keyword, LocalDateTime searchedAt) {
		return ProductSearchLog.of(keyword, ProductSearchKeyword.from(keyword).orElseThrow(), 1, 10, false, searchedAt);
	}

	private void create(String name) {
		productService.create(1L, new ProductCreateCommand(name, "<p>설명</p>", ProductCategoryCode.FRUIT,
			ProductRegionCode.GYEONGBUK, ProductStatusCode.ON_SALE, ProductVisibilityCode.VISIBLE, List.of(IMAGE),
			List.of(new ProductOptionCommand(null, "기본", null, 20_000L, 10))));
	}

	// 검색 행이 상품을 외래키로 가리키므로 먼저 지운다. TRUNCATE는 FULLTEXT 인덱스를 새로 만들어 앞선 테스트가 점수에 섞이지 않는다
	private void deleteAll() {
		jdbcTemplate.execute("TRUNCATE TABLE " + SEARCH_TABLE);
		// 태그와 태그 판정 행이 상품을 외래키로 가리키므로 먼저 지운다
		productTagRepository.deleteAllInBatch();
		productTagJudgmentRepository.deleteAllInBatch();
		productRepository.deleteAll();
		jdbcTemplate.execute("TRUNCATE TABLE " + LOG_TABLE);
	}
}
