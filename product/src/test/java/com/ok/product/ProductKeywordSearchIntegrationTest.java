package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductOptionJpaRepository;
import com.ok.product.product.adapter.out.persistence.BuyerProductQueryJdbcRepository;
import com.ok.product.product.adapter.out.persistence.ProductSearchJpaRepository;
import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ProductCategoryGroupCode;
import com.ok.product.product.application.dto.ProductCreateCommand;
import com.ok.product.product.application.dto.ProductOptionCommand;
import com.ok.product.product.application.dto.ProductRegionCode;
import com.ok.product.product.application.dto.ProductSearchQuery;
import com.ok.product.product.application.dto.ProductSortCode;
import com.ok.product.product.application.dto.ProductStatusCode;
import com.ok.product.product.application.dto.ProductSummaryResult;
import com.ok.product.product.application.dto.ProductVisibilityCode;
import com.ok.product.product.application.service.ProductService;
import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.product.product.domain.ProductSort;
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
	BuyerProductQueryJdbcRepository buyerProductQueryRepository;

	@Autowired
	ProductJpaRepository productRepository;

	@Autowired
	ProductOptionJpaRepository productOptionRepository;

	@Autowired
	ProductSearchJpaRepository productSearchRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

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
		Page<Long> lowFloor = buyerProductQueryRepository.findVisibleProductIds(keyword, null, null, null,
			ProductSort.RELEVANCE, 0.1, pageable);

		// when
		Page<Long> sut = buyerProductQueryRepository.findVisibleProductIds(keyword, null, null, null,
			ProductSort.RELEVANCE, 0.5, pageable);

		// then
		// 하한이 낮으면 자모 조각 하나만 겹친 상품(샤인머스캣, 시금치)까지 개수에 든다
		assertThat(lowFloor.getTotalElements()).isEqualTo(3);
		assertThat(sut.getContent()).containsExactly(siraegi);
		assertThat(sut.getTotalElements()).isEqualTo(1);
	}

	@Test
	void 글자가_그대로_겹친_상품이_있으면_자모로만_걸린_상품은_결과와_전체_개수에서_빠진다() {
		// given
		Long grape = create("김천 청포도 2kg", ProductCategoryCode.FRUIT, ProductRegionCode.GYEONGBUK, "2kg");
		Long cheongdoPeach = create("청도 복숭아 4kg", ProductCategoryCode.FRUIT, ProductRegionCode.GYEONGBUK, "4kg");
		// 하한을 낮춰 자모 조각 몇 개만 겹친 상품도 하한을 넘게 한다
		double lowFloor = 0.1;
		PageRequest pageable = PageRequest.of(0, 1);
		// "청뽀도"는 두 상품 모두와 글자 조각이 겹치지 않아 자모로만 찾는다. 청도 복숭아도 자모로 걸린다는 것을 먼저 확인한다
		List<Long> typo = buyerProductQueryRepository.findVisibleProductIds(
			ProductSearchKeyword.from("청뽀도").orElseThrow(), null, null, null, ProductSort.RELEVANCE, lowFloor,
			PageRequest.of(0, 20)).getContent();

		// when
		Page<Long> sut = buyerProductQueryRepository.findVisibleProductIds(
			ProductSearchKeyword.from("청포도").orElseThrow(), null, null, null, ProductSort.RELEVANCE, lowFloor, pageable);

		// then
		assertThat(typo).contains(grape, cheongdoPeach);
		assertThat(sut.getContent()).containsExactly(grape);
		assertThat(sut.getTotalElements()).isEqualTo(1);
	}

	@Test
	void 지역_조건을_걸어도_조건을_걸지_않았을_때_없던_상품이_나오지_않는다() {
		// given
		Long grape = create("김천 청포도 2kg", ProductCategoryCode.FRUIT, ProductRegionCode.GYEONGBUK, "2kg");
		// 자모로만 걸리는 상품이 제주에만 있다. 조건 안에서 글자 일치를 찾으면 청포도가 빠져 자모 결과로 되돌아간다
		Long jejuPeach = create("청도 복숭아 4kg", ProductCategoryCode.FRUIT, ProductRegionCode.JEJU, "4kg");
		// 하한을 낮춰 자모 조각 몇 개만 겹친 상품도 하한을 넘게 한다
		double lowFloor = 0.1;
		ProductSearchKeyword keyword = ProductSearchKeyword.from("청포도").orElseThrow();
		PageRequest pageable = PageRequest.of(0, 20);

		// when
		Page<Long> unfiltered = buyerProductQueryRepository.findVisibleProductIds(keyword, null, null, null,
			ProductSort.RELEVANCE, lowFloor, pageable);
		Page<Long> jeju = buyerProductQueryRepository.findVisibleProductIds(keyword, null, null,
			ProductRegionCode.JEJU.toDomain(), ProductSort.RELEVANCE, lowFloor, pageable);
		Page<Long> gyeongbuk = buyerProductQueryRepository.findVisibleProductIds(keyword, null, null,
			ProductRegionCode.GYEONGBUK.toDomain(), ProductSort.RELEVANCE, lowFloor, pageable);

		// then
		assertThat(unfiltered.getContent()).containsExactly(grape);
		assertThat(jeju.getContent()).isEmpty();
		assertThat(jeju.getTotalElements()).isZero();
		assertThat(gyeongbuk.getContent()).containsExactly(grape);
		assertThat(unfiltered.getContent()).doesNotContain(jejuPeach);
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
		// 검색어가 있을 때와 없을 때 노출 조건이 같은지 보려고 검색어 없는 목록을 기준으로 삼는다
		List<Long> listed = productService.searchVisibleProducts(
				new ProductSearchQuery(null, null, null, null, null, 0, 100))
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
	void 검색어가_없거나_공백_기호뿐이면_전체_목록이_최신순이고_품절_상품만_뒤로_간다(String keyword) throws Exception {
		// given
		sellOut(apple);

		// when & then
		mockMvc.perform(get("/api/v1/products").param("keyword", keyword))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].productId").value(contains(
				bruisedOptionPeach.intValue(), bruisedPear.intValue(), shiitake.intValue(), spinach.intValue(),
				siraegi.intValue(), jeonnamTangerine.intValue(), tangerine.intValue(), shineMuscat.intValue(),
				apple.intValue())))
			.andExpect(jsonPath("$.totalElements").value(9));
	}

	static Stream<String> 검색어가_없는_것으로_보는_값() {
		return Stream.of("", "   ", "@@@", "!!!");
	}

	@ParameterizedTest
	@MethodSource("정확도순이_아닌_정렬")
	void 가격순과_최신순에서도_점수가_하한보다_낮은_상품은_결과와_전체_개수에서_빠진다(ProductSortCode sort) {
		// given
		ProductSearchKeyword keyword = ProductSearchKeyword.from("시레기").orElseThrow();
		// 하한이 낮으면 자모 조각 하나만 겹친 상품(샤인머스캣, 시금치)까지 나온다
		Page<Long> lowFloor = buyerProductQueryRepository.findVisibleProductIds(keyword, null, null, null,
			sort.toDomain(), 0.1, PageRequest.of(0, 20));

		// when
		Page<ProductSummaryResult> sut = productService.searchVisibleProducts(
			new ProductSearchQuery("시레기", null, null, null, sort, 0, 1));

		// then
		assertThat(lowFloor.getContent()).containsExactlyInAnyOrder(siraegi, shineMuscat, spinach);
		assertThat(sut.getContent()).extracting(ProductSummaryResult::productId).containsExactly(siraegi);
		assertThat(sut.getTotalElements()).isEqualTo(1);
	}

	static Stream<ProductSortCode> 정확도순이_아닌_정렬() {
		return Stream.of(ProductSortCode.PRICE_ASC, ProductSortCode.PRICE_DESC, ProductSortCode.LATEST);
	}

	@ParameterizedTest
	@MethodSource("정렬별_기대_순서")
	void 품절_상품은_점수가_더_높아도_어떤_정렬에서든_판매_가능_상품_뒤에_온다(ProductSortCode sort,
		List<String> beforeSoldOut, List<String> afterSoldOut) {
		// given
		String name = "부여 방울토마토 2kg";
		Long cheap = create(name, "가정용", 10_000L);
		Long expensive = create(name, "가정용", 50_000L);
		// 옵션명에도 검색어가 들어가 가장 점수가 높고, 가격은 가운데, id는 가장 크다
		Long soldOut = create(name, name, 30_000L);
		Map<String, Long> ids = Map.of("cheap", cheap, "expensive", expensive, "soldOut", soldOut);
		List<Long> before = search("방울토마토", sort);
		sellOut(soldOut);

		// when
		List<Long> sut = search("방울토마토", sort);

		// then
		assertThat(before).containsExactlyElementsOf(beforeSoldOut.stream().map(ids::get).toList());
		assertThat(sut).containsExactlyElementsOf(afterSoldOut.stream().map(ids::get).toList());
	}

	static Stream<Arguments> 정렬별_기대_순서() {
		// cheap과 expensive는 점수와 평점이 같아 정확도순에서는 id가 큰 expensive가 먼저다
		return Stream.of(
			Arguments.of(ProductSortCode.RELEVANCE, List.of("soldOut", "expensive", "cheap"),
				List.of("expensive", "cheap", "soldOut")),
			Arguments.of(ProductSortCode.PRICE_ASC, List.of("cheap", "soldOut", "expensive"),
				List.of("cheap", "expensive", "soldOut")),
			Arguments.of(ProductSortCode.PRICE_DESC, List.of("expensive", "soldOut", "cheap"),
				List.of("expensive", "cheap", "soldOut")),
			Arguments.of(ProductSortCode.LATEST, List.of("soldOut", "expensive", "cheap"),
				List.of("expensive", "cheap", "soldOut")));
	}

	@Test
	void 점수가_같으면_리뷰_수로_보정한_평점이_높은_상품이_먼저이고_평점까지_같으면_id가_큰_상품이_먼저다() {
		// given
		String name = "무안 햇양파 3kg";
		Long oneFiveStar = create(name, "가정용", 20_000L);
		Long manyFourEight = create(name, "가정용", 20_000L);
		Long noReviewOld = create(name, "가정용", 20_000L);
		Long noReviewNew = create(name, "가정용", 20_000L);
		// rating_sum은 별 0.5 단위의 1~10 척도다. 별 5.0 한 개는 10, 별 4.8 평균 100개는 960이다
		transactionTemplate.executeWithoutResult(status -> {
			productRepository.addReviewStats(oneFiveStar, 1, 10);
			productRepository.addReviewStats(manyFourEight, 100, 960);
		});

		// when
		List<Long> relevance = search("햇양파", ProductSortCode.RELEVANCE);
		List<Long> priceAsc = search("햇양파", ProductSortCode.PRICE_ASC);

		// then
		assertThat(relevance).containsExactly(manyFourEight, oneFiveStar, noReviewNew, noReviewOld);
		assertThat(priceAsc).containsExactly(manyFourEight, oneFiveStar, noReviewNew, noReviewOld);
	}

	@Test
	void 한_글자_검색어는_그_글자로_정해_둔_품목의_상품만_찾고_그_글자로_시작하는_다른_품목은_찾지_않는다() throws Exception {
		// given
		Long pear = create("나주 신고배 7.5kg", ProductCategoryCode.FRUIT, ProductRegionCode.JEONNAM, "선물용");
		Long cabbage = create("해남 절임배추 10kg", ProductCategoryCode.VEGETABLE, ProductRegionCode.JEONNAM, "10kg");
		Long pearJuice = create("나주 배즙 30포", ProductCategoryCode.PROCESSED_AGRI, ProductRegionCode.JEONNAM, "30포");
		Long mandarin = create("제주 하우스 귤 3kg", ProductCategoryCode.FRUIT, ProductRegionCode.JEJU, "3kg");
		Long sweetPersimmon = create("청도 단감 5kg", ProductCategoryCode.FRUIT, ProductRegionCode.GYEONGBUK, "5kg");
		Long potato = create("강원 감자 5kg", ProductCategoryCode.VEGETABLE, ProductRegionCode.GANGWON, "5kg");

		// when & then
		mockMvc.perform(get("/api/v1/products").param("keyword", "배"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].productId").value(contains(pear.intValue(), bruisedPear.intValue())))
			.andExpect(jsonPath("$.totalElements").value(2));
		// "하우스감귤"의 가장 긴 품목은 "감귤"이지만 "귤"로 찾도록 정해 두어 함께 나온다
		mockMvc.perform(get("/api/v1/products").param("keyword", "귤"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].productId").value(
				contains(mandarin.intValue(), jeonnamTangerine.intValue(), tangerine.intValue())))
			.andExpect(jsonPath("$.totalElements").value(3));
		assertThat(search("감")).containsExactly(sweetPersimmon);
		assertThat(search("배")).doesNotContain(cabbage, pearJuice);
		assertThat(search("감")).doesNotContain(potato);
	}

	@ParameterizedTest
	@MethodSource("공백_기호를_빼면_한_글자인_검색어")
	void 공백_기호를_빼면_한_글자인_검색어도_한_글자_규칙으로_찾는다(String keyword) {
		// given
		create("해남 절임배추 10kg", ProductCategoryCode.VEGETABLE, ProductRegionCode.JEONNAM, "10kg");

		// when
		List<Long> sut = search(keyword);

		// then
		assertThat(sut).containsExactly(bruisedPear);
	}

	static Stream<String> 공백_기호를_빼면_한_글자인_검색어() {
		return Stream.of(" 배 ", "배!", "(배)");
	}

	@Test
	void 한_글자_검색에서도_필터_정렬_품절_순서_페이지와_전체_개수가_맞다() {
		// given
		// 대표 품목이 "배"인 상품만 쓴다. 기본 상품 중 "나주 흠과 배 7kg"(전남, 2만 원)도 함께 걸린다
		Long cheap = create("나주 신고배 5kg", ProductCategoryCode.FRUIT, ProductRegionCode.JEONNAM, "가정용",
			ProductStatusCode.ON_SALE, ProductVisibilityCode.VISIBLE, 10_000L);
		Long expensive = create("상주 원황배 7kg", ProductCategoryCode.FRUIT, ProductRegionCode.GYEONGBUK, "선물용",
			ProductStatusCode.ON_SALE, ProductVisibilityCode.VISIBLE, 50_000L);
		Long soldOut = create("천안 배 5kg", ProductCategoryCode.FRUIT, ProductRegionCode.CHUNGNAM, "가정용",
			ProductStatusCode.ON_SALE, ProductVisibilityCode.VISIBLE, 5_000L);
		Long reviewed = create("안성 배 3kg", ProductCategoryCode.FRUIT, ProductRegionCode.GYEONGGI, "가정용",
			ProductStatusCode.ON_SALE, ProductVisibilityCode.VISIBLE, 30_000L);
		sellOut(soldOut);
		transactionTemplate.executeWithoutResult(status -> productRepository.addReviewStats(reviewed, 10, 100));

		// when
		List<Long> relevance = search("배", ProductSortCode.RELEVANCE);
		List<Long> priceAsc = search("배", ProductSortCode.PRICE_ASC);
		List<Long> priceDesc = search("배", ProductSortCode.PRICE_DESC);
		List<Long> latest = search("배", ProductSortCode.LATEST);
		List<Long> jeonnam = search("배", null, null, ProductRegionCode.JEONNAM);
		Page<ProductSummaryResult> secondPage = productService.searchVisibleProducts(
			new ProductSearchQuery("배", null, null, null, ProductSortCode.PRICE_ASC, 1, 2));

		// then
		// 한 글자 검색은 점수가 모두 같아 정확도순이 보정 평점, 그다음 id 순이다
		assertThat(relevance).containsExactly(reviewed, expensive, cheap, bruisedPear, soldOut);
		assertThat(priceAsc).containsExactly(cheap, bruisedPear, reviewed, expensive, soldOut);
		assertThat(priceDesc).containsExactly(expensive, reviewed, bruisedPear, cheap, soldOut);
		assertThat(latest).containsExactly(reviewed, expensive, cheap, bruisedPear, soldOut);
		assertThat(jeonnam).containsExactly(cheap, bruisedPear);
		assertThat(secondPage.getContent()).extracting(ProductSummaryResult::productId)
			.containsExactly(reviewed, expensive);
		assertThat(secondPage.getTotalElements()).isEqualTo(5);
	}

	private List<Long> search(String keyword, ProductSortCode sort) {
		return productService.searchVisibleProducts(new ProductSearchQuery(keyword, null, null, null, sort, 0, 20))
			.map(ProductSummaryResult::productId)
			.getContent();
	}

	// 주문 배정으로 모든 재고를 잡아 판매 가능 수량을 0으로 만든다
	private void sellOut(Long productId) {
		transactionTemplate.executeWithoutResult(status -> productRepository.findById(productId).orElseThrow()
			.getOptions().forEach(option -> productOptionRepository.allocate(option.getId(), option.getTotalStock())));
	}

	private List<Long> search(String keyword) {
		return search(keyword, null, null, null);
	}

	private List<Long> search(String keyword, ProductCategoryGroupCode categoryGroup, ProductCategoryCode category,
		ProductRegionCode region) {
		return productService.searchVisibleProducts(
				new ProductSearchQuery(keyword, categoryGroup, category, region, null, 0, 20))
			.map(ProductSummaryResult::productId)
			.getContent();
	}

	private Long create(String name, ProductCategoryCode category, ProductRegionCode region, String optionName) {
		return create(name, category, region, optionName, ProductStatusCode.ON_SALE, ProductVisibilityCode.VISIBLE);
	}

	private Long create(String name, ProductCategoryCode category, ProductRegionCode region, String optionName,
		ProductStatusCode status, ProductVisibilityCode visibility) {
		return create(name, category, region, optionName, status, visibility, 20_000L);
	}

	private Long create(String name, String optionName, long salePrice) {
		return create(name, ProductCategoryCode.VEGETABLE, ProductRegionCode.CHUNGNAM, optionName,
			ProductStatusCode.ON_SALE, ProductVisibilityCode.VISIBLE, salePrice);
	}

	private Long create(String name, ProductCategoryCode category, ProductRegionCode region, String optionName,
		ProductStatusCode status, ProductVisibilityCode visibility, long salePrice) {
		return productService.create(SELLER_ID, new ProductCreateCommand(name, "<p>설명</p>", category, region, status,
			visibility, List.of(IMAGE), List.of(new ProductOptionCommand(null, optionName, null, salePrice, 10))));
	}

	private void deleteAll() {
		// 검색 행이 상품을 외래키로 가리키므로 먼저 지운다. DELETE로 지우면 FULLTEXT 인덱스에 지운 문서가 남아
		// 점수(IDF)가 앞서 돈 테스트에 따라 달라진다. TRUNCATE는 인덱스를 새로 만들어 매번 같은 점수가 나오게 한다
		jdbcTemplate.execute("TRUNCATE TABLE " + SEARCH_TABLE);
		productRepository.deleteAll();
	}
}
