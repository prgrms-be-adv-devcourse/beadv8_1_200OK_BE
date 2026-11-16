package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductSearchJpaRepository;
import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ProductCreateCommand;
import com.ok.product.product.application.dto.ProductOptionCommand;
import com.ok.product.product.application.dto.ProductRegionCode;
import com.ok.product.product.application.dto.ProductStatusCode;
import com.ok.product.product.application.dto.ProductUpdateCommand;
import com.ok.product.product.application.dto.ProductVisibilityCode;
import com.ok.product.product.application.service.ProductService;
import com.ok.product.product.domain.ProductSearch;
import com.ok.product.product.domain.SearchTextNormalizer;
import com.ok.testsupport.AbstractIntegrationTest;

// 상품 저장과 검색 행이 함께 커밋·롤백되는지 보려고 테스트 메서드에 @Transactional을 붙이지 않는다
class ProductSearchIndexIntegrationTest extends AbstractIntegrationTest {

	// application-test.yml의 버킷(test-mock-bucket)과 리전으로 만든 업로드 URL
	private static final String IMAGE = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg";
	private static final Long SELLER_ID = 1L;
	private static final String NAME = "[서귀포] 당일수확 하우스감귤 5kg";
	private static final String SEARCH_TABLE = ProductSchema.NAME + ".product_search";
	private static final String BLOCKING_CHECK = "ck_product_search_blocked_for_test";

	@Autowired
	ProductService productService;

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
	void 상품을_등록하면_정규화한_검색_행이_생긴다() {
		// when
		Long productId = productService.create(SELLER_ID, createCommand(NAME, "가정용 3kg"));

		// then
		ProductSearch sut = productSearchRepository.findById(productId).orElseThrow();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getNameCompact()).isEqualTo("서귀포당일수확하우스감귤5kg");
			softly.assertThat(sut.getNameJamo()).isEqualTo("ㅅㅓㄱㅟㅍㅗㄷㅏㅇㅇㅣㄹㅅㅜㅎㅘㄱㅎㅏㅇㅜㅅㅡㄱㅏㅁㄱㅠㄹ5kg");
			softly.assertThat(sut.getSearchBody()).isEqualTo("가정용 3kg 과일");
			softly.assertThat(sut.getKeywords()).isEmpty();
			softly.assertThat(sut.getSearchItem()).isEqualTo("감귤");
			softly.assertThat(sut.getNormalizerVersion()).isEqualTo(SearchTextNormalizer.VERSION);
		});
	}

	@Test
	void 상품명을_고치면_이름_컬럼과_품목이_새_이름으로_다시_계산된다() {
		// given
		Long productId = productService.create(SELLER_ID, createCommand(NAME, "가정용 3kg"));
		Long optionId = firstOptionId(productId);

		// when
		productService.update(SELLER_ID, productId,
			updateCommand(productId, "한라봉 3kg", new ProductOptionCommand(optionId, "가정용 3kg", null, 20_000L, 10)));

		// then
		ProductSearch sut = productSearchRepository.findById(productId).orElseThrow();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getNameCompact()).isEqualTo("한라봉3kg");
			softly.assertThat(sut.getNameJamo()).isEqualTo("ㅎㅏㄴㄹㅏㅂㅗㅇ3kg");
			softly.assertThat(sut.getSearchItem()).isEqualTo("한라봉");
			softly.assertThat(sut.getSearchBody()).isEqualTo("가정용 3kg 과일");
		});
	}

	@Test
	void 옵션명만_고치면_search_body만_바뀌고_이름_컬럼은_그대로다() {
		// given
		Long productId = productService.create(SELLER_ID, createCommand(NAME, "가정용 3kg"));
		Long optionId = firstOptionId(productId);
		ProductSearch before = productSearchRepository.findById(productId).orElseThrow();

		// when
		productService.update(SELLER_ID, productId,
			updateCommand(productId, NAME, new ProductOptionCommand(optionId, "선물용 3kg", null, 20_000L, 10)));

		// then
		ProductSearch sut = productSearchRepository.findById(productId).orElseThrow();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getSearchBody()).isEqualTo("선물용 3kg 과일");
			softly.assertThat(sut.getNameCompact()).isEqualTo(before.getNameCompact());
			softly.assertThat(sut.getNameJamo()).isEqualTo(before.getNameJamo());
		});
	}

	@Test
	void 가격만_바꿔도_검색_행을_다시_계산하고_keywords는_지우지_않는다() {
		// given
		Long productId = productService.create(SELLER_ID, createCommand(NAME, "가정용 3kg"));
		Long optionId = firstOptionId(productId);
		// 다시 계산되는지 보려고 이름 컬럼을 엉뚱한 값으로 바꿔 두고, 태그 판정이 채운 것처럼 keywords를 넣어 둔다
		jdbcTemplate.update("UPDATE " + SEARCH_TABLE + " SET name_compact = '옛값', keywords = '새콤 달콤' WHERE product_id = ?",
			productId);

		// when
		productService.update(SELLER_ID, productId,
			updateCommand(productId, NAME, new ProductOptionCommand(optionId, "가정용 3kg", null, 25_000L, 10)));

		// then
		ProductSearch sut = productSearchRepository.findById(productId).orElseThrow();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getNameCompact()).isEqualTo("서귀포당일수확하우스감귤5kg");
			softly.assertThat(sut.getKeywords()).isEqualTo("새콤 달콤");
		});
	}

	@Test
	void 판매_상태_노출_변경과_삭제는_검색_행을_바꾸지_않는다() {
		// given
		Long productId = productService.create(SELLER_ID, createCommand(NAME, "가정용 3kg"));
		// 다시 계산하면 덮어써지는 값을 넣어 둬서, 검색 행을 다시 계산했는지 드러나게 한다
		jdbcTemplate.update("UPDATE " + SEARCH_TABLE + " SET name_compact = '옛값' WHERE product_id = ?", productId);
		Map<String, Object> before = searchRow(productId);

		// when
		productService.changeStatus(SELLER_ID, productId, ProductStatusCode.ON_SALE);
		productService.changeVisibility(SELLER_ID, productId, ProductVisibilityCode.VISIBLE);
		productService.delete(SELLER_ID, productId);

		// then
		assertThat(searchRow(productId)).isEqualTo(before);
	}

	@Test
	void 검색_행_저장이_실패하면_상품도_저장되지_않는다() {
		// given
		long productCount = productRepository.count();
		// 서비스 빈을 바꾸면 컨텍스트가 새로 뜨므로, 어떤 행도 통과하지 못하는 CHECK 제약으로 검색 행 INSERT만 실패시킨다.
		// 트리거는 바이너리 로그가 켜진 컨테이너에서 SUPER 권한이 없어 만들 수 없다
		jdbcTemplate.execute("ALTER TABLE " + SEARCH_TABLE + " ADD CONSTRAINT " + BLOCKING_CHECK + " CHECK (product_id < 0)");

		try {
			// when & then
			assertThatThrownBy(() -> productService.create(SELLER_ID, createCommand(NAME, "가정용 3kg")))
				.isInstanceOf(DataAccessException.class);
			assertThat(productRepository.count()).isEqualTo(productCount);
		} finally {
			// 컨테이너를 모든 통합 테스트가 같이 쓰므로 제약을 남기면 다른 테스트의 등록이 모두 실패한다
			jdbcTemplate.execute("ALTER TABLE " + SEARCH_TABLE + " DROP CHECK " + BLOCKING_CHECK);
		}
	}

	@Test
	void 검색_테이블에_ngram_FULLTEXT_인덱스_4개가_있다() {
		// when
		List<String> fulltextIndexes = jdbcTemplate.queryForList("""
			SELECT index_name FROM information_schema.statistics
			WHERE table_schema = ? AND table_name = 'product_search' AND index_type = 'FULLTEXT'
			ORDER BY index_name
			""", String.class, ProductSchema.NAME);
		String createTable = jdbcTemplate.queryForObject("SHOW CREATE TABLE " + SEARCH_TABLE,
			(rs, rowNum) -> rs.getString(2));

		// then
		assertThat(fulltextIndexes).containsExactly("ft_product_search_keywords", "ft_product_search_name_compact",
			"ft_product_search_name_jamo", "ft_product_search_search_body");
		assertThat(createTable).containsSubsequence(
			"FULLTEXT KEY `ft_product_search_name_compact` (`name_compact`) /*!50100 WITH PARSER `ngram` */",
			"FULLTEXT KEY `ft_product_search_name_jamo` (`name_jamo`) /*!50100 WITH PARSER `ngram` */",
			"FULLTEXT KEY `ft_product_search_search_body` (`search_body`) /*!50100 WITH PARSER `ngram` */",
			"FULLTEXT KEY `ft_product_search_keywords` (`keywords`) /*!50100 WITH PARSER `ngram` */");
	}

	private ProductCreateCommand createCommand(String name, String optionName) {
		return new ProductCreateCommand(name, "<p>제주 직송</p>", ProductCategoryCode.FRUIT, ProductRegionCode.JEJU,
			null, null, List.of(IMAGE), List.of(new ProductOptionCommand(null, optionName, null, 20_000L, 10)));
	}

	private ProductUpdateCommand updateCommand(Long productId, String name, ProductOptionCommand option) {
		Integer version = productRepository.findById(productId).orElseThrow().getVersion();
		return new ProductUpdateCommand(version, name, "<p>제주 직송</p>", ProductCategoryCode.FRUIT,
			ProductRegionCode.JEJU, null, null, List.of(IMAGE), List.of(option));
	}

	private Long firstOptionId(Long productId) {
		return jdbcTemplate.queryForObject("SELECT id FROM " + ProductSchema.NAME + ".product_option WHERE product_id = ?",
			Long.class, productId);
	}

	private Map<String, Object> searchRow(Long productId) {
		return jdbcTemplate.queryForMap("SELECT * FROM " + SEARCH_TABLE + " WHERE product_id = ?", productId);
	}
}
