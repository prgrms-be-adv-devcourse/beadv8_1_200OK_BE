package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductKeywordSearchJdbcRepository;
import com.ok.product.product.adapter.out.persistence.ProductSearchJpaRepository;
import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ProductCategoryGroupCode;
import com.ok.product.product.application.dto.ProductCreateCommand;
import com.ok.product.product.application.dto.ProductOptionCommand;
import com.ok.product.product.application.dto.ProductRegionCode;
import com.ok.product.product.application.dto.ProductSearchQuery;
import com.ok.product.product.application.dto.ProductStatusCode;
import com.ok.product.product.application.dto.ProductSummaryResult;
import com.ok.product.product.application.dto.ProductVisibilityCode;
import com.ok.product.product.application.service.ProductService;
import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.testsupport.AbstractIntegrationTest;

// FULLTEXT 인덱스는 커밋된 행만 찾으므로 테스트 메서드에 @Transactional을 붙이지 않는다.
// 검색 행이 생기도록 상품은 서비스로 등록한다
@AutoConfigureMockMvc
class ProductKeywordSearchIntegrationTest extends AbstractIntegrationTest {

	// application-test.yml의 버킷(test-mock-bucket)과 리전으로 만든 업로드 URL
	private static final String IMAGE = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg";
	private static final Long SELLER_ID = 1L;
	private static final String SEARCH_TABLE = ProductSchema.NAME + ".product_search";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductService productService;

	@Autowired
	ProductKeywordSearchJdbcRepository productKeywordSearchRepository;

	@Autowired
	ProductJpaRepository productRepository;

	@Autowired
	ProductSearchJpaRepository productSearchRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	Long shineMuscat;
	Long tangerine;
	Long jeonnamTangerine;
	Long siraegi;
	Long spinach;
	Long shiitake;
	Long apple;
	Long bruisedPear;
	Long bruisedOptionPeach;

	// 목록 테스트는 다른 테스트가 남긴 상품이 섞이면 깨지므로 시작 전에도 비운다
	@BeforeEach
	void setUp() {
		deleteAll();
		shineMuscat = create("[김천] 샤인머스캣 2kg", ProductCategoryCode.FRUIT, ProductRegionCode.GYEONGBUK, "가정용 2kg");
		tangerine = create("[서귀포] 하우스감귤 3kg", ProductCategoryCode.FRUIT, ProductRegionCode.JEJU, "선물용 3kg");
		jeonnamTangerine = create("고흥 노지감귤 5kg", ProductCategoryCode.FRUIT, ProductRegionCode.JEONNAM, "가정용 5kg");
		siraegi = create("양구 시래기 500g", ProductCategoryCode.WILD_GREENS, ProductRegionCode.GANGWON, "건조 500g");
		spinach = create("남해 시금치 1kg", ProductCategoryCode.VEGETABLE, ProductRegionCode.GYEONGNAM, "단 1kg");
		shiitake = create("장흥 표고 1kg", ProductCategoryCode.MUSHROOM, ProductRegionCode.JEONNAM, "꽃송이 혼합");
		apple = create("청송 사과 5kg", ProductCategoryCode.FRUIT, ProductRegionCode.GYEONGBUK, "부사 5kg");
		bruisedPear = create("나주 흠과 배 7kg", ProductCategoryCode.FRUIT, ProductRegionCode.JEONNAM, "가정용 7kg");
		bruisedOptionPeach = create("영천 복숭아 4kg", ProductCategoryCode.FRUIT, ProductRegionCode.GYEONGBUK, "흠과 4kg");
	}

	@AfterEach
	void tearDown() {
		deleteAll();
	}

	@Test
	void 상품명을_그대로_검색하면_그_상품이_1위다() {
		// when
		List<Long> sut = search("[서귀포] 하우스감귤 3kg");

		// then
		assertThat(sut).first().isEqualTo(tangerine);
	}

	@Test
	void 상품명에_든_말로_검색하면_그_상품이_1위이고_목록_응답_모양은_그대로다() throws Exception {
		// when & then
		mockMvc.perform(get("/api/v1/products").param("keyword", "샤인머스캣"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].productId").value(shineMuscat.intValue()))
			.andExpect(jsonPath("$.content[0].name").value("[김천] 샤인머스캣 2kg"))
			.andExpect(jsonPath("$.content[0].thumbnailUrl").value(IMAGE))
			.andExpect(jsonPath("$.content[0].minSalePrice").value(20_000))
			.andExpect(jsonPath("$.content[0].minPriceListPrice").value(20_000))
			.andExpect(jsonPath("$.content[0].discountRate").value(0))
			.andExpect(jsonPath("$.content[0].category").value("FRUIT"))
			.andExpect(jsonPath("$.content[0].region").value("GYEONGBUK"))
			.andExpect(jsonPath("$.content[0].status").value("ON_SALE"))
			.andExpect(jsonPath("$.content[0].soldOut").value(false))
			.andExpect(jsonPath("$.content[0].reviewCount").value(0))
			.andExpect(jsonPath("$.content[0].averageRating").value(0.0))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalElements").isNumber())
			.andExpect(jsonPath("$.totalPages").isNumber());
	}

	@Test
	void 띄어쓰기가_달라도_같은_상품을_찾는다() {
		// when
		List<Long> spaced = search("하우스 감귤");
		List<Long> joined = search("하우스감귤");
		List<Long> longer = search("서귀포하우스감귤");

		// then
		assertThat(spaced).first().isEqualTo(tangerine);
		assertThat(joined).first().isEqualTo(tangerine);
		assertThat(longer).first().isEqualTo(tangerine);
	}

	@Test
	void 음절이_하나도_겹치지_않는_오타도_자모로_찾는다() {
		// "시레기"와 "시래기"는 2글자 조각(시레·레기 / 시래·래기)이 하나도 같지 않아 자모 컬럼으로만 걸린다
		// when
		List<Long> sut = search("시레기");

		// then
		assertThat(sut).first().isEqualTo(siraegi);
	}

	@Test
	void 옵션명에만_있는_말로도_찾고_상품명에_있는_상품보다_아래다() {
		// when
		List<Long> sut = search("흠과");

		// then
		assertThat(sut).startsWith(bruisedPear, bruisedOptionPeach);
	}

	@Test
	void 카테고리_한글명으로_찾는다() {
		// when
		List<Long> sut = search("버섯");

		// then
		assertThat(sut).first().isEqualTo(shiitake);
	}

	@Test
	void 점수가_하한보다_낮은_상품은_결과와_전체_개수에서_빠진다() {
		// given
		ProductSearchKeyword keyword = ProductSearchKeyword.from("시레기").orElseThrow();
		// 한 페이지를 1개로 해야 첫 페이지가 다 차서 전체 개수를 따로 센다
		PageRequest pageable = PageRequest.of(0, 1);
		Page<Long> lowFloor = productKeywordSearchRepository.findVisibleProductIds(keyword, null, null, null, 0.1,
			pageable);

		// when
		Page<Long> sut = productKeywordSearchRepository.findVisibleProductIds(keyword, null, null, null, 0.5, pageable);

		// then
		// 하한이 낮으면 자모 조각 하나만 겹친 상품(샤인머스캣, 시금치)까지 개수에 든다
		assertThat(lowFloor.getTotalElements()).isEqualTo(3);
		assertThat(sut.getContent()).containsExactly(siraegi);
		assertThat(sut.getTotalElements()).isEqualTo(1);
	}

	@Test
	void 맞는_상품이_없으면_빈_목록과_전체_개수_0이다() throws Exception {
		// when & then
		mockMvc.perform(get("/api/v1/products").param("keyword", "컴퓨터"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isEmpty())
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@ParameterizedTest
	@MethodSource("연산자_문자가_붙은_검색어")
	void FULLTEXT_연산자_문자는_연산자로_해석하지_않고_빼고_찾는다(String keyword) throws Exception {
		// when & then
		mockMvc.perform(get("/api/v1/products").param("keyword", keyword))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].productId").value(contains(apple.intValue())));
	}

	static Stream<String> 연산자_문자가_붙은_검색어() {
		return Stream.of("사과*", "-사과", "+사과", "\"사과", "(사과", "사과~", "<사과>", "@사과");
	}

	@ParameterizedTest
	@MethodSource("SQL_주입_시도")
	void SQL_주입_문자열은_일반_검색어로_처리되고_테이블은_그대로다(String keyword) throws Exception {
		// given
		long searchRows = productSearchRepository.count();

		// when & then
		mockMvc.perform(get("/api/v1/products").param("keyword", keyword))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + SEARCH_TABLE, Long.class)).isEqualTo(searchRows);
	}

	static Stream<String> SQL_주입_시도() {
		return Stream.of("' OR 1=1 --", "') OR ('1'='1", "1'; DROP TABLE product_search; --");
	}

	@Test
	void 검색어에_걸려도_기존_목록에_나오지_않는_숨김_삭제_상품은_나오지_않는다() {
		// given
		String name = "괴산 대학찰옥수수 10개";
		Long onSale = create(name, ProductCategoryCode.GRAIN, ProductRegionCode.CHUNGBUK, "10개");
		Long ready = create(name, ProductCategoryCode.GRAIN, ProductRegionCode.CHUNGBUK, "10개", ProductStatusCode.READY,
			ProductVisibilityCode.VISIBLE);
		Long hidden = create(name, ProductCategoryCode.GRAIN, ProductRegionCode.CHUNGBUK, "10개", ProductStatusCode.ON_SALE,
			ProductVisibilityCode.HIDDEN);
		Long deleted = create(name, ProductCategoryCode.GRAIN, ProductRegionCode.CHUNGBUK, "10개");
		productService.delete(SELLER_ID, deleted);
		List<Long> sameName = List.of(onSale, ready, hidden, deleted);
		// 노출 조건이 기존 목록(ProductQuerydslRepository)과 같은지 보려고 그 결과를 기준으로 삼는다
		List<Long> listed = productService.searchVisibleProducts(new ProductSearchQuery(null, null, null, null, 0, 100))
			.map(ProductSummaryResult::productId).filter(sameName::contains).toList();

		// when
		List<Long> sut = search("대학찰옥수수");

		// then
		assertThat(listed).containsExactlyInAnyOrder(onSale, ready);
		assertThat(sut).containsExactlyInAnyOrderElementsOf(listed);
	}

	@Test
	void 검색어와_필터를_함께_주면_모든_조건을_만족하는_상품만_나온다() {
		// when
		List<Long> jeju = search("감귤", null, null, ProductRegionCode.JEJU);
		List<Long> vegetable = search("감귤", null, ProductCategoryCode.VEGETABLE, null);
		List<Long> all = search("감귤", ProductCategoryGroupCode.AGRICULTURE, ProductCategoryCode.FRUIT,
			ProductRegionCode.JEONNAM);

		// then
		assertThat(search("감귤")).contains(tangerine, jeonnamTangerine);
		assertThat(jeju).containsExactly(tangerine);
		assertThat(vegetable).isEmpty();
		assertThat(all).containsExactly(jeonnamTangerine);
	}

	@ParameterizedTest
	@MethodSource("검색어가_없는_것으로_보는_값")
	void 검색어가_없거나_공백_기호뿐이면_기존_목록과_같은_결과와_순서다(String keyword) throws Exception {
		// when & then
		mockMvc.perform(get("/api/v1/products").param("keyword", keyword))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].productId").value(contains(
				bruisedOptionPeach.intValue(), bruisedPear.intValue(), apple.intValue(), shiitake.intValue(),
				spinach.intValue(), siraegi.intValue(), jeonnamTangerine.intValue(), tangerine.intValue(),
				shineMuscat.intValue())))
			.andExpect(jsonPath("$.totalElements").value(9));
	}

	static Stream<String> 검색어가_없는_것으로_보는_값() {
		return Stream.of("", "   ", "@@@", "!!!");
	}

	private List<Long> search(String keyword) {
		return search(keyword, null, null, null);
	}

	private List<Long> search(String keyword, ProductCategoryGroupCode categoryGroup, ProductCategoryCode category,
		ProductRegionCode region) {
		return productService.searchVisibleProducts(new ProductSearchQuery(keyword, categoryGroup, category, region, 0, 20))
			.map(ProductSummaryResult::productId)
			.getContent();
	}

	private Long create(String name, ProductCategoryCode category, ProductRegionCode region, String optionName) {
		return create(name, category, region, optionName, ProductStatusCode.ON_SALE, ProductVisibilityCode.VISIBLE);
	}

	private Long create(String name, ProductCategoryCode category, ProductRegionCode region, String optionName,
		ProductStatusCode status, ProductVisibilityCode visibility) {
		return productService.create(SELLER_ID, new ProductCreateCommand(name, "<p>설명</p>", category, region, status,
			visibility, List.of(IMAGE), List.of(new ProductOptionCommand(null, optionName, null, 20_000L, 10))));
	}

	private void deleteAll() {
		// 검색 행이 상품을 외래키로 가리키므로 먼저 지운다. DELETE로 지우면 FULLTEXT 인덱스에 지운 문서가 남아
		// 점수(IDF)가 앞서 돈 테스트에 따라 달라진다. TRUNCATE는 인덱스를 새로 만들어 매번 같은 점수가 나오게 한다
		jdbcTemplate.execute("TRUNCATE TABLE " + SEARCH_TABLE);
		productRepository.deleteAll();
	}
}
