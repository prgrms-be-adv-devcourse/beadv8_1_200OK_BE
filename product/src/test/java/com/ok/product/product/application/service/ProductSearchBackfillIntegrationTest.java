package com.ok.product.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ok.product.ProductSchema;
import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductSearchJpaRepository;
import com.ok.product.product.application.dto.ProductSearchBackfillResult;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductSearch;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.domain.SearchTextNormalizer;
import com.ok.testsupport.AbstractIntegrationTest;

// 묶음마다 커밋·롤백되는지 보려고 테스트 메서드에 @Transactional을 붙이지 않는다.
// 묶음 크기를 줄여 부르려고 서비스와 같은 패키지에 둔다
class ProductSearchBackfillIntegrationTest extends AbstractIntegrationTest {

	private static final Long SELLER_ID = 1L;
	private static final String NAME = "[서귀포] 당일수확 하우스감귤 5kg";
	private static final String SEARCH_TABLE = ProductSchema.NAME + ".product_search";
	private static final String BLOCKING_CHECK = "ck_product_search_backfill_blocked_for_test";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 12, 0);

	@Autowired
	ProductSearchBackfillService productSearchBackfillService;

	@Autowired
	ProductJpaRepository productRepository;

	@Autowired
	ProductSearchJpaRepository productSearchRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		// 검색 행이 상품을 외래키로 가리키므로 먼저 지운다
		productSearchRepository.deleteAll();
		productRepository.deleteAll();
	}

	@Test
	void 검색_행이_없는_노출_숨김_판매중지_상품을_모두_채운다() {
		// given
		Long visibleId = saveProduct(ProductStatus.ON_SALE, ProductVisibility.VISIBLE);
		Long hiddenId = saveProduct(ProductStatus.ON_SALE, ProductVisibility.HIDDEN);
		Long pausedId = saveProduct(ProductStatus.PAUSED, ProductVisibility.VISIBLE);

		// when
		ProductSearchBackfillResult sut = productSearchBackfillService.backfill();

		// then
		assertThat(sut).isEqualTo(new ProductSearchBackfillResult(3, 0));
		assertThat(productSearchRepository.findAllById(List.of(visibleId, hiddenId, pausedId)))
			.hasSize(3)
			.allSatisfy(productSearch -> {
				assertThat(productSearch.getNameCompact()).isEqualTo("서귀포당일수확하우스감귤5kg");
				assertThat(productSearch.getNormalizerVersion()).isEqualTo(SearchTextNormalizer.VERSION);
			});
	}

	@Test
	void 삭제된_상품은_채우지_않는다() {
		// given
		Product product = Product.create(SELLER_ID, NAME, "<p>제주 직송</p>", ProductCategory.FRUIT, ProductRegion.JEJU,
			List.of(ProductOptionSpec.newOption("가정용 3kg", 25_000L, 20_000L, 10)), List.of("product/a.jpg"));
		product.delete(NOW);
		Long deletedId = productRepository.save(product).getId();

		// when
		ProductSearchBackfillResult sut = productSearchBackfillService.backfill();

		// then
		assertThat(sut).isEqualTo(new ProductSearchBackfillResult(0, 0));
		assertThat(productSearchRepository.findById(deletedId)).isEmpty();
	}

	@Test
	void 정규화_버전이_낮은_행은_다시_계산하고_keywords는_지우지_않는다() {
		// given
		Long productId = saveProduct(ProductStatus.ON_SALE, ProductVisibility.VISIBLE);
		insertSearchRow(productId, "옛값", SearchTextNormalizer.VERSION - 1);

		// when
		ProductSearchBackfillResult sut = productSearchBackfillService.backfill();

		// then
		assertThat(sut).isEqualTo(new ProductSearchBackfillResult(1, 0));
		ProductSearch productSearch = productSearchRepository.findById(productId).orElseThrow();
		assertThat(productSearch).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(productSearch.getNameCompact()).isEqualTo("서귀포당일수확하우스감귤5kg");
			softly.assertThat(productSearch.getNormalizerVersion()).isEqualTo(SearchTextNormalizer.VERSION);
			softly.assertThat(productSearch.getKeywords()).isEqualTo("새콤 달콤");
		});
	}

	@Test
	void 옛_품목_규칙으로_저장된_search_item도_다시_계산한다() {
		// given
		Long productId = saveProduct(ProductStatus.ON_SALE, ProductVisibility.VISIBLE);
		insertSearchRow(productId, "서귀포당일수확하우스감귤5kg", 1);
		// 버전 1은 가장 긴 품목을 그대로 저장했다
		jdbcTemplate.update("UPDATE " + SEARCH_TABLE + " SET search_item = '감귤' WHERE product_id = ?", productId);

		// when
		ProductSearchBackfillResult sut = productSearchBackfillService.backfill();

		// then
		assertThat(sut).isEqualTo(new ProductSearchBackfillResult(1, 0));
		assertThat(productSearchRepository.findById(productId).orElseThrow().getSearchItem()).isEqualTo("귤");
	}

	@Test
	void 최신_버전_행은_건드리지_않는다() {
		// given
		Long productId = saveProduct(ProductStatus.ON_SALE, ProductVisibility.VISIBLE);
		// 다시 계산하면 덮어써지는 값을 넣어 둬서, 건드렸는지 드러나게 한다
		insertSearchRow(productId, "옛값", SearchTextNormalizer.VERSION);
		Map<String, Object> before = searchRow(productId);

		// when
		ProductSearchBackfillResult sut = productSearchBackfillService.backfill();

		// then
		assertThat(sut).isEqualTo(new ProductSearchBackfillResult(0, 0));
		assertThat(searchRow(productId)).isEqualTo(before);
	}

	@Test
	void 두_번_돌려도_검색_행이_같고_두_번째는_처리하지_않는다() {
		// given
		Long visibleId = saveProduct(ProductStatus.ON_SALE, ProductVisibility.VISIBLE);
		Long hiddenId = saveProduct(ProductStatus.READY, ProductVisibility.HIDDEN);
		productSearchBackfillService.backfill();
		List<Map<String, Object>> before = List.of(searchRow(visibleId), searchRow(hiddenId));

		// when
		ProductSearchBackfillResult sut = productSearchBackfillService.backfill();

		// then
		assertThat(sut).isEqualTo(new ProductSearchBackfillResult(0, 0));
		assertThat(List.of(searchRow(visibleId), searchRow(hiddenId))).isEqualTo(before);
	}

	@Test
	void 대상이_묶음_크기보다_많으면_다음_묶음으로_넘어가_모두_채운다() {
		// given
		List<Long> productIds = IntStream.range(0, 5)
			.mapToObj(i -> saveProduct(ProductStatus.ON_SALE, ProductVisibility.VISIBLE))
			.toList();

		// when
		ProductSearchBackfillResult sut = productSearchBackfillService.backfill(2);

		// then
		assertThat(sut).isEqualTo(new ProductSearchBackfillResult(5, 0));
		assertThat(productSearchRepository.findAllById(productIds)).hasSize(5);
	}

	@Test
	void 한_묶음이_실패해도_다음_묶음은_처리한다() {
		// given
		List<Long> productIds = IntStream.range(0, 4)
			.mapToObj(i -> saveProduct(ProductStatus.ON_SALE, ProductVisibility.VISIBLE))
			.toList();
		// 서비스 빈을 바꾸면 컨텍스트가 새로 뜨므로, 첫 상품의 검색 행만 막는 CHECK 제약으로 첫 묶음을 실패시킨다
		jdbcTemplate.execute("ALTER TABLE " + SEARCH_TABLE + " ADD CONSTRAINT " + BLOCKING_CHECK
			+ " CHECK (product_id <> " + productIds.getFirst() + ")");

		ProductSearchBackfillResult sut;
		try {
			// when
			sut = productSearchBackfillService.backfill(2);
		} finally {
			// 컨테이너를 모든 통합 테스트가 같이 쓰므로 제약을 남기면 다른 테스트의 검색 행 저장이 실패한다
			jdbcTemplate.execute("ALTER TABLE " + SEARCH_TABLE + " DROP CHECK " + BLOCKING_CHECK);
		}

		// then
		assertThat(sut).isEqualTo(new ProductSearchBackfillResult(2, 2));
		// 첫 묶음은 통째로 롤백되어 막히지 않은 두 번째 상품도 비어 있다
		assertThat(productSearchRepository.findAllById(productIds))
			.extracting(ProductSearch::getProductId)
			.containsExactlyInAnyOrder(productIds.get(2), productIds.get(3));
	}

	private Long saveProduct(ProductStatus status, ProductVisibility visibility) {
		// 서비스로 등록하면 검색 행이 함께 생기므로 저장소로 바로 넣는다
		Product product = Product.create(SELLER_ID, NAME, "<p>제주 직송</p>", ProductCategory.FRUIT, ProductRegion.JEJU,
			List.of(ProductOptionSpec.newOption("가정용 3kg", 25_000L, 20_000L, 10)), List.of("product/a.jpg"));
		product.changeStatus(status);
		product.changeVisibility(visibility);
		return productRepository.save(product).getId();
	}

	private void insertSearchRow(Long productId, String nameCompact, int normalizerVersion) {
		jdbcTemplate.update("INSERT INTO " + SEARCH_TABLE
				+ " (product_id, name_compact, name_jamo, search_body, keywords, search_item, normalizer_version,"
				+ " created_at, updated_at) VALUES (?, ?, '옛값', '옛값', '새콤 달콤', NULL, ?, ?, ?)",
			productId, nameCompact, normalizerVersion, NOW, NOW);
	}

	private Map<String, Object> searchRow(Long productId) {
		return jdbcTemplate.queryForMap("SELECT * FROM " + SEARCH_TABLE + " WHERE product_id = ?", productId);
	}
}
