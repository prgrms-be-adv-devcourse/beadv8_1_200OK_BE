package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJudgmentJpaRepository;
import com.ok.product.product.application.dto.ProductTagBackfillResult;
import com.ok.product.product.application.dto.ProductTagScores;
import com.ok.product.product.application.dto.ProductTagTarget;
import com.ok.product.product.application.port.in.ProductTagJudgmentUseCase;
import com.ok.product.product.application.service.ProductTagService;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductTag;
import com.ok.product.product.domain.ProductTagJudgment;
import com.ok.product.product.domain.ProductTagJudgmentStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeProductTagJudge;
import com.ok.testsupport.TestAccessTokens;

// 작업자(스케줄러)는 테스트에서 꺼져 있어 판정은 유스케이스를 직접 불러 돌린다
class ProductTagJudgmentIntegrationTest extends AbstractIntegrationTest {

	private static final String IMAGE = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg";
	private static final Long SELLER_ID = 1L;
	private static final String SEARCH_TABLE = ProductSchema.NAME + ".product_search";
	private static final String JUDGMENT_TABLE = ProductSchema.NAME + ".product_tag_judgment";

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	@Autowired
	ProductTagJudgmentUseCase productTagJudgmentUseCase;

	@Autowired
	ProductTagService productTagService;

	@Autowired
	FakeProductTagJudge fakeProductTagJudge;

	@Autowired
	ProductJpaRepository productRepository;

	@Autowired
	ProductTagJpaRepository productTagRepository;

	@Autowired
	ProductTagJudgmentJpaRepository productTagJudgmentRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	TransactionTemplate transactionTemplate;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		deleteAll();
	}

	@AfterEach
	void tearDown() {
		fakeProductTagJudge.clear();
		deleteAll();
	}

	@Test
	void 등록한_상품을_작업자가_판정하면_태그와_검색_키워드가_바뀌고_아이_간식_검색에서_맨_위에_나온다() throws Exception {
		// given
		Long tangerineId = create("제주 감귤", "2kg 소과");
		Long pepperId = create("청양고추", "1kg");
		fakeProductTagJudge.respond("제주 감귤", Map.of("아이 간식", 0.95, "새콤달콤", 0.93, "선물용", 0.6));
		fakeProductTagJudge.respond("청양고추", Map.of("김장·김치용", 0.97));

		// when
		productTagJudgmentUseCase.judgePending();

		// then
		ProductTagJudgment sut = judgment(tangerineId);
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(tagIds(tangerineId)).containsExactlyInAnyOrder("T03", "T18");
			softly.assertThat(tagIds(pepperId)).containsExactly("T10");
			softly.assertThat(sut.getStatus()).isEqualTo(ProductTagJudgmentStatus.COMPLETED);
			softly.assertThat(sut.getTagVersion()).isEqualTo("v3");
			softly.assertThat(sut.getModel()).isEqualTo(FakeProductTagJudge.MODEL);
			softly.assertThat(sut.getJudgedAt()).isNotNull();
			softly.assertThat(sut.getTopTagId()).isEqualTo("T03");
			softly.assertThat(sut.getTopScore()).isEqualTo(0.95);
			softly.assertThat(keywords(tangerineId)).contains("아이 간식", "어린이 간식", "새콤달콤").doesNotContain("선물");
		});
		mockMvc.perform(get("/api/v1/products").param("keyword", "아이 간식"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].productId").value(tangerineId));
	}

	@Test
	void 판정기가_실패해도_상품_등록은_성공하고_판정은_최종_실패로_남는다() throws Exception {
		// given
		fakeProductTagJudge.fail("제주 감귤");

		// when
		Long productId = create("제주 감귤", "2kg 소과");
		productTagJudgmentUseCase.judgePending();

		// then
		ProductTagJudgment sut = judgment(productId);
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(productRepository.existsById(productId)).isTrue();
			softly.assertThat(sut.getStatus()).isEqualTo(ProductTagJudgmentStatus.FAILED);
			softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
			softly.assertThat(sut.getLastError()).contains("Fake 태그 판정 실패");
			softly.assertThat(tagIds(productId)).isEmpty();
		});
	}

	@Test
	void 판정하는_동안_상품명이_바뀌면_결과를_버리고_새_이름으로_다시_판정한다() throws Exception {
		// given
		Long productId = create("제주 감귤", "2kg 소과");
		ProductTagTarget target = productTagService.findPendingTargets(20).getFirst();
		update(productId, "제주 한라봉", "2kg 소과").andExpect(status().isNoContent());

		// when
		productTagService.complete(target, new ProductTagScores(Map.of("T03", 0.95), "v3", FakeProductTagJudge.MODEL));

		// then
		ProductTagJudgment discarded = judgment(productId);
		assertSoftly(softly -> {
			softly.assertThat(discarded.getStatus()).isEqualTo(ProductTagJudgmentStatus.PENDING);
			softly.assertThat(discarded.getRequestedAt()).isAfter(target.requestedAt());
			softly.assertThat(discarded.getJudgedAt()).isNull();
			softly.assertThat(tagIds(productId)).isEmpty();
		});
		fakeProductTagJudge.respond("제주 한라봉", Map.of("선물용", 0.96));
		productTagJudgmentUseCase.judgePending();
		assertSoftly(softly -> {
			softly.assertThat(fakeProductTagJudge.judgedNames()).containsExactly("제주 한라봉");
			softly.assertThat(tagIds(productId)).containsExactly("T01");
		});
	}

	// complete()는 중간에 멈출 수 없어, 같은 잠금 순서(판정 행 → 검색 행)를 쓰는 트랜잭션으로 작업자를 대신한다.
	// 판매자 수정이 검색 행을 먼저 잠그면 작업자의 검색 행 UPDATE에서 교착(1213)이 나 한쪽이 실패한다
	@Test
	void 작업자가_판정_결과를_저장하는_동안_판매자가_상품명을_고쳐도_교착_없이_둘_다_끝난다() throws Exception {
		// given
		Long productId = create("제주 감귤", "2kg 소과");
		CountDownLatch workerLocked = new CountDownLatch(1);
		CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
			jdbcTemplate.queryForList("SELECT id FROM " + JUDGMENT_TABLE + " WHERE product_id = ? FOR UPDATE", productId);
			workerLocked.countDown();
			awaitLockWait();
			jdbcTemplate.update("UPDATE " + SEARCH_TABLE + " SET keywords = ? WHERE product_id = ?", "아이 간식", productId);
		}));
		assertThat(workerLocked.await(5, TimeUnit.SECONDS)).isTrue();

		// when
		ResultActions sellerUpdate = update(productId, "제주 한라봉", "2kg 소과");

		// then
		worker.get(10, TimeUnit.SECONDS);
		sellerUpdate.andExpect(status().isNoContent());
		assertThat(judgment(productId).getStatus()).isEqualTo(ProductTagJudgmentStatus.PENDING);
	}

	@Test
	void 수정할_때_상품명과_옵션명이_그대로면_판정을_다시_요청하지_않는다() throws Exception {
		// given
		Long productId = create("제주 감귤", "2kg 소과");
		fakeProductTagJudge.respond("제주 감귤", Map.of("아이 간식", 0.95));
		productTagJudgmentUseCase.judgePending();
		ProductTagJudgment before = judgment(productId);

		// when
		update(productId, "제주 감귤", "2kg 소과", 25_000L).andExpect(status().isNoContent());

		// then
		ProductTagJudgment sut = judgment(productId);
		assertSoftly(softly -> {
			softly.assertThat(sut.getStatus()).isEqualTo(ProductTagJudgmentStatus.COMPLETED);
			softly.assertThat(sut.getRequestedAt()).isEqualTo(before.getRequestedAt());
		});
	}

	@Test
	void 수정할_때_옵션명이_바뀌면_판정을_다시_요청한다() throws Exception {
		// given
		Long productId = create("제주 감귤", "2kg 소과");
		fakeProductTagJudge.respond("제주 감귤", Map.of("아이 간식", 0.95));
		productTagJudgmentUseCase.judgePending();

		// when
		update(productId, "제주 감귤", "5kg 선물용 대과").andExpect(status().isNoContent());

		// then
		ProductTagJudgment sut = judgment(productId);
		assertSoftly(softly -> {
			softly.assertThat(sut.getStatus()).isEqualTo(ProductTagJudgmentStatus.PENDING);
			softly.assertThat(sut.getAttemptCount()).isZero();
			// 다시 판정할 때까지 지난 판정의 태그는 그대로 쓴다
			softly.assertThat(tagIds(productId)).containsExactly("T03");
		});
	}

	@Test
	void 판정_결과를_저장해도_상품_version이_그대로라_판매자_수정이_동시_수정으로_실패하지_않는다() throws Exception {
		// given
		Long productId = create("제주 감귤", "2kg 소과");
		int versionBefore = version(productId);
		fakeProductTagJudge.respond("제주 감귤", Map.of("아이 간식", 0.95));

		// when
		productTagJudgmentUseCase.judgePending();

		// then
		assertThat(version(productId)).isEqualTo(versionBefore);
		update(productId, "제주 감귤", "2kg 소과", 25_000L).andExpect(status().isNoContent());
	}

	@Test
	void 삭제된_상품은_판정기를_부르지_않고_판정_요청을_지운다() throws Exception {
		// given
		Long productId = create("제주 감귤", "2kg 소과");
		mockMvc.perform(delete("/api/v1/products/{productId}", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER")))
			.andExpect(status().isNoContent());

		// when
		productTagJudgmentUseCase.judgePending();

		// then
		assertSoftly(softly -> {
			softly.assertThat(fakeProductTagJudge.judgedNames()).isEmpty();
			softly.assertThat(productTagJudgmentRepository.findByProductId(productId)).isEmpty();
		});
	}

	@Test
	void 백필은_판정_행이_없거나_목록_버전이_다르거나_최종_실패한_상품만_대기로_넣고_두_번_돌려도_결과가_같다() throws Exception {
		// given
		Long missing = create("제주 감귤", "2kg");
		Long staleVersion = create("청양고추", "1kg");
		Long failed = create("고구마", "3kg");
		Long current = create("양파", "5kg");
		Long deleted = create("대파", "1단");
		fakeProductTagJudge.respond("청양고추", Map.of());
		fakeProductTagJudge.respond("양파", Map.of());
		fakeProductTagJudge.fail("고구마");
		fakeProductTagJudge.fail("대파");
		productTagJudgmentUseCase.judgePending();
		jdbcTemplate.update("DELETE FROM " + JUDGMENT_TABLE + " WHERE product_id IN (?, ?)", missing, deleted);
		jdbcTemplate.update("UPDATE " + JUDGMENT_TABLE + " SET tag_version = 'v2' WHERE product_id = ?", staleVersion);
		jdbcTemplate.update("UPDATE " + ProductSchema.NAME + ".product SET deleted_at = NOW(6) WHERE id = ?", deleted);

		// when
		ProductTagBackfillResult first = productTagService.backfill();
		Map<Long, String> rowsAfterFirst = judgmentRows();
		ProductTagBackfillResult sut = productTagService.backfill();

		// then
		assertSoftly(softly -> {
			softly.assertThat(first).isEqualTo(new ProductTagBackfillResult(1, 2));
			softly.assertThat(sut).isEqualTo(new ProductTagBackfillResult(0, 0));
			softly.assertThat(judgmentRows()).isEqualTo(rowsAfterFirst);
			softly.assertThat(judgmentRows().keySet()).containsExactlyInAnyOrder(missing, staleVersion, failed, current);
			softly.assertThat(judgment(missing).getStatus()).isEqualTo(ProductTagJudgmentStatus.PENDING);
			softly.assertThat(judgment(staleVersion).getStatus()).isEqualTo(ProductTagJudgmentStatus.PENDING);
			softly.assertThat(judgment(failed).getStatus()).isEqualTo(ProductTagJudgmentStatus.PENDING);
			softly.assertThat(judgment(failed).getAttemptCount()).isZero();
			softly.assertThat(judgment(current).getStatus()).isEqualTo(ProductTagJudgmentStatus.COMPLETED);
		});
	}

	private Long create(String name, String optionName) throws Exception {
		String body = """
			{
			  "name": "%s",
			  "descriptionHtml": "<p>설명</p>",
			  "category": "FRUIT",
			  "region": "JEJU",
			  "status": "ON_SALE",
			  "visibility": "VISIBLE",
			  "imageUrls": ["%s"],
			  "options": [{ "name": "%s", "salePrice": 20000, "totalStock": 10 }]
			}
			""".formatted(name, IMAGE, optionName);
		String response = mockMvc.perform(post("/api/v1/products")
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number)JsonPath.read(response, "$.productId")).longValue();
	}

	private ResultActions update(Long productId, String name, String optionName) throws Exception {
		return update(productId, name, optionName, 20_000L);
	}

	// 옵션은 이름만 바꿔도 같은 옵션으로 수정되게 기존 옵션 ID를 함께 보낸다
	private ResultActions update(Long productId, String name, String optionName, long salePrice) throws Exception {
		Long optionId = transactionTemplate.execute(status ->
			productRepository.findById(productId).orElseThrow().activeOptions().getFirst().getId());
		String body = """
			{
			  "version": %d,
			  "name": "%s",
			  "descriptionHtml": "<p>설명</p>",
			  "category": "FRUIT",
			  "region": "JEJU",
			  "imageUrls": ["%s"],
			  "options": [{ "id": %d, "name": "%s", "salePrice": %d, "totalStock": 10 }]
			}
			""".formatted(version(productId), name, IMAGE, optionId, optionName, salePrice);
		return mockMvc.perform(put("/api/v1/products/{productId}", productId)
			.header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER"))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	// 판매자 트랜잭션이 작업자가 쥔 판정 행 잠금을 기다리기 시작할 때까지 기다린다. 그 전에 작업자가 끝나면 두 트랜잭션이 겹치지 않는다.
	// 테스트 계정에 PROCESS 권한이 없어 INNODB_TRX는 못 보고, 권한 없이 같은 계정의 스레드가 보이는 PROCESSLIST로 판정 행 INSERT가 실행 중인지 본다
	private void awaitLockWait() {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Integer waiting = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
				+ "WHERE ID <> CONNECTION_ID() AND COMMAND = 'Query' AND INFO LIKE 'INSERT INTO %product_tag_judgment%'",
				Integer.class);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.onSpinWait();
		}
		throw new IllegalStateException("판매자 수정이 잠금 대기에 들어가지 않았습니다.");
	}

	private int version(Long productId) {
		return productRepository.findById(productId).map(Product::getVersion).orElseThrow();
	}

	private ProductTagJudgment judgment(Long productId) {
		return productTagJudgmentRepository.findByProductId(productId).orElseThrow();
	}

	private List<String> tagIds(Long productId) {
		return productTagRepository.findByProductId(productId).stream().map(ProductTag::getTagId).toList();
	}

	private String keywords(Long productId) {
		return jdbcTemplate.queryForObject("SELECT keywords FROM " + SEARCH_TABLE + " WHERE product_id = ?",
			String.class, productId);
	}

	// 백필이 바꾸는 칼럼을 모두 담아, 두 번째 실행이 아무것도 바꾸지 않았는지 본다
	private Map<Long, String> judgmentRows() {
		return jdbcTemplate.query("SELECT product_id, status, tag_version, attempt_count, next_attempt_at, requested_at, "
				+ "last_error FROM " + JUDGMENT_TABLE,
			(rs, rowNum) -> Map.entry(rs.getLong("product_id"), String.join("|", rs.getString("status"),
				String.valueOf(rs.getString("tag_version")), rs.getString("attempt_count"),
				rs.getString("next_attempt_at"), rs.getString("requested_at"), String.valueOf(rs.getString("last_error")))))
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	// 검색 행·태그·판정 행이 상품을 외래키로 가리키므로 먼저 지운다. 검색 행은 TRUNCATE해야 FULLTEXT 점수가 앞선 테스트에 섞이지 않는다
	private void deleteAll() {
		jdbcTemplate.execute("TRUNCATE TABLE " + SEARCH_TABLE);
		productTagRepository.deleteAllInBatch();
		productTagJudgmentRepository.deleteAllInBatch();
		productRepository.deleteAll();
	}
}
