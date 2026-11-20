package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.ok.common.jpa.QueryCountContext;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.adapter.out.persistence.ProductOptionJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJudgmentJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductSearchJpaRepository;
import com.ok.testsupport.AbstractIntegrationTest;

@AutoConfigureMockMvc
class BuyerProductIntegrationTest extends AbstractIntegrationTest {

	private static final String BUCKET_URL = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductJpaRepository productRepository;

	@Autowired
	ProductTagJpaRepository productTagRepository;

	@Autowired
	ProductTagJudgmentJpaRepository productTagJudgmentRepository;

	@Autowired
	ProductOptionJpaRepository productOptionRepository;

	@Autowired
	ProductSearchJpaRepository productSearchRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbcTemplate;

	// 목록 테스트는 다른 테스트가 남긴 상품이 섞이면 깨지므로 시작 전에 비운다
	@BeforeEach
	void setUp() {
		// 태그와 태그 판정 행이 상품을 외래키로 가리키므로 먼저 지운다
		productTagRepository.deleteAllInBatch();
		productTagJudgmentRepository.deleteAllInBatch();
		productRepository.deleteAll();
	}

	@Test
	void 목록에는_노출된_상품만_최신순으로_나오고_카드_값이_채워진다() throws Exception {
		// given
		Long apple = save(visible("청송 꿀사과", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("5kg", 49_000L, 49_000L, 10),
				ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 10))));
		Long carrot = save(visible("제주 당근", ProductCategory.VEGETABLE, ProductRegion.JEJU, option(10)));
		save(product("숨긴 상품", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));
		Product deleted = visible("삭제한 상품", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10));
		deleted.delete(LocalDateTime.now());
		save(deleted);

		// when & then
		mockMvc.perform(get("/api/v1/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].productId").value(contains(carrot.intValue(), apple.intValue())))
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalPages").value(1))
			.andExpect(jsonPath("$.content[1].name").value("청송 꿀사과"))
			.andExpect(jsonPath("$.content[1].thumbnailUrl").value(BUCKET_URL + "a.jpg"))
			.andExpect(jsonPath("$.content[1].minSalePrice").value(32_000))
			.andExpect(jsonPath("$.content[1].minPriceListPrice").value(40_000))
			.andExpect(jsonPath("$.content[1].discountRate").value(20))
			.andExpect(jsonPath("$.content[1].category").value("FRUIT"))
			.andExpect(jsonPath("$.content[1].region").value("GYEONGBUK"))
			.andExpect(jsonPath("$.content[1].status").value("READY"))
			.andExpect(jsonPath("$.content[1].soldOut").value(false))
			.andExpect(jsonPath("$.content[1].reviewCount").value(0))
			.andExpect(jsonPath("$.content[1].averageRating").value(0.0));
	}

	@Test
	void 카테고리와_산지로_거른다() throws Exception {
		// given
		Long apple = save(visible("청송 꿀사과", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));
		Long carrot = save(visible("제주 당근", ProductCategory.VEGETABLE, ProductRegion.JEJU, option(10)));
		Long tangerine = save(visible("제주 감귤", ProductCategory.FRUIT, ProductRegion.JEJU, option(10)));

		// when & then
		mockMvc.perform(get("/api/v1/products").param("categoryGroup", "AGRICULTURE"))
			.andExpect(jsonPath("$.content[*].productId")
				.value(contains(tangerine.intValue(), carrot.intValue(), apple.intValue())));
		mockMvc.perform(get("/api/v1/products").param("category", "FRUIT"))
			.andExpect(jsonPath("$.content[*].productId").value(contains(tangerine.intValue(), apple.intValue())));
		mockMvc.perform(get("/api/v1/products").param("region", "JEJU"))
			.andExpect(jsonPath("$.content[*].productId").value(contains(tangerine.intValue(), carrot.intValue())));
		mockMvc.perform(get("/api/v1/products").param("category", "FRUIT").param("region", "JEJU"))
			.andExpect(jsonPath("$.content[*].productId").value(contains(tangerine.intValue())));
	}

	@Test
	void 삭제되지_않은_옵션의_판매_가능_수량이_모두_0이면_품절이다() throws Exception {
		// given
		Long noStock = save(visible("재고 없음", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(0)));
		Long allAllocated = save(visible("모두 배정됨", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(5)));
		Long optionId = transactionTemplate.execute(
			status -> productRepository.findById(allAllocated).orElseThrow().getOptions().getFirst().getId());
		transactionTemplate.executeWithoutResult(status -> productOptionRepository.allocate(optionId, 5));
		Long onSale = save(visible("판매 중", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(5)));

		// when & then
		mockMvc.perform(get("/api/v1/products"))
			.andExpect(jsonPath("$.content[*].productId")
				.value(contains(onSale.intValue(), allAllocated.intValue(), noStock.intValue())))
			.andExpect(jsonPath("$.content[*].soldOut").value(contains(false, true, true)));
		mockMvc.perform(get("/api/v1/products/{productId}", allAllocated))
			.andExpect(jsonPath("$.soldOut").value(true))
			.andExpect(jsonPath("$.options[0].soldOut").value(true));
	}

	@Test
	void 목록의_품절_순서는_상세의_품절_판정과_같다() throws Exception {
		// given
		Long allSoldOut = save(visible("모든 옵션 품절", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 0),
				ProductOptionSpec.newOption("5kg", 49_000L, 49_000L, 0))));
		Long partlySoldOut = save(visible("일부 옵션만 품절", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 0),
				ProductOptionSpec.newOption("5kg", 49_000L, 49_000L, 5))));
		Long stockOnlyInDeleted = save(visible("삭제된 옵션에만 재고", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 0),
				ProductOptionSpec.newOption("5kg", 49_000L, 49_000L, 10))));
		jdbcTemplate.update("UPDATE " + ProductSchema.NAME + ".product_option SET deleted_at = NOW(6)"
			+ " WHERE product_id = ? AND name = '5kg'", stockOnlyInDeleted);
		Long allAllocated = save(visible("배정으로 가용 재고 0", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(5)));
		Long optionId = transactionTemplate.execute(
			status -> productRepository.findById(allAllocated).orElseThrow().getOptions().getFirst().getId());
		transactionTemplate.executeWithoutResult(status -> productOptionRepository.allocate(optionId, 5));

		// when & then
		// soldOut 값은 Product.isSoldOut()이 정하고 순서는 SQL이 정한다. 두 판정이 다르면 soldOut이 섞여 나온다
		mockMvc.perform(get("/api/v1/products"))
			.andExpect(jsonPath("$.content[*].productId").value(contains(partlySoldOut.intValue(),
				allAllocated.intValue(), stockOnlyInDeleted.intValue(), allSoldOut.intValue())))
			.andExpect(jsonPath("$.content[*].soldOut").value(contains(false, true, true, true)));
	}

	@Test
	void 검색_행이_없는_상품도_검색어_없는_목록에는_나온다() throws Exception {
		// given
		Long withoutSearchRow = save(visible("청송 꿀사과", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));

		// when & then
		assertThat(productSearchRepository.findById(withoutSearchRow)).isEmpty();
		mockMvc.perform(get("/api/v1/products"))
			.andExpect(jsonPath("$.content[*].productId").value(contains(withoutSearchRow.intValue())));
	}

	@Test
	void 가격과_평점이_모두_같아도_페이지를_넘기면_중복_누락_없이_모두_나오고_마지막_페이지를_넘으면_빈_목록이다()
		throws Exception {
		// given
		List<Long> saved = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			saved.add(save(visible("같은 가격 " + i, ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10))));
		}
		List<Long> paged = new ArrayList<>();

		// when
		for (int page = 0; page < 3; page++) {
			paged.addAll(productIdsOf(mockMvc.perform(get("/api/v1/products")
					.param("sort", "PRICE_ASC").param("page", String.valueOf(page)).param("size", "10"))
				.andExpect(status().isOk())
				.andReturn()));
		}

		// then
		assertThat(paged).containsExactlyElementsOf(saved.stream().sorted(Comparator.reverseOrder()).toList());
		mockMvc.perform(get("/api/v1/products").param("sort", "PRICE_ASC").param("page", "3").param("size", "10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isEmpty())
			.andExpect(jsonPath("$.totalElements").value(25));
	}

	@Test
	void 페이지_크기만큼_나누고_전체_개수를_알려준다() throws Exception {
		// given
		Long first = save(visible("첫 번째", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));
		save(visible("두 번째", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));
		save(visible("세 번째", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));

		// when & then
		mockMvc.perform(get("/api/v1/products").param("page", "1").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].productId").value(contains(first.intValue())))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(2))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.totalPages").value(2));
	}

	@ParameterizedTest
	@MethodSource("잘못된_목록_조회_파라미터")
	void 없는_필터_정렬_값이나_범위를_벗어난_페이지는_400_ProblemDetail이다(String name, String value, String type)
		throws Exception {
		mockMvc.perform(get("/api/v1/products").param(name, value))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://api.example.com/problems/" + type))
			.andExpect(jsonPath("$.status").value(400));
	}

	// 없는 enum 값은 타입 변환 오류(invalid-query-parameter), 범위 위반은 메서드 검증 오류(bad-request)로 간다
	static Stream<Arguments> 잘못된_목록_조회_파라미터() {
		return Stream.of(
			Arguments.of("category", "APPLE", "invalid-query-parameter"),
			Arguments.of("sort", "POPULAR", "invalid-query-parameter"),
			Arguments.of("page", "-1", "bad-request"),
			Arguments.of("size", "0", "bad-request"),
			Arguments.of("size", "101", "bad-request"));
	}

	@Test
	void 검색어는_50자까지_받고_51자면_page_size와_같은_400_ProblemDetail이다() throws Exception {
		// when & then
		mockMvc.perform(get("/api/v1/products").param("keyword", "가".repeat(50)))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/products").param("keyword", "가".repeat(51)))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://api.example.com/problems/bad-request"))
			.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void 검색어_길이는_받은_그대로_재므로_앞뒤_공백까지_51자면_400이다() throws Exception {
		// 설계 5.2대로 Spec에서 다듬기 전 값을 잰다. 시나리오 S24의 "다듬은 뒤 길이"와 다르다
		// when & then
		mockMvc.perform(get("/api/v1/products").param("keyword", " " + "가".repeat(49)))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/products").param("keyword", " " + "가".repeat(49) + " "))
			.andExpect(status().isBadRequest());
	}

	@Test
	void 상세는_노출된_상품의_옵션과_이미지를_화면_순서대로_보여준다() throws Exception {
		// given
		Product product = visible("청송 꿀사과", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg 특품", 40_000L, 32_000L, 100),
				ProductOptionSpec.newOption("5kg 특품", null, 49_000L, 0)));
		product.changeStatus(ProductStatus.ON_SALE);
		Long productId = save(product);

		// when & then
		mockMvc.perform(get("/api/v1/products/{productId}", productId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.productId").value(productId))
			.andExpect(jsonPath("$.name").value("청송 꿀사과"))
			.andExpect(jsonPath("$.descriptionHtml").value("<p>설명</p>"))
			.andExpect(jsonPath("$.categoryGroup").value("AGRICULTURE"))
			.andExpect(jsonPath("$.category").value("FRUIT"))
			.andExpect(jsonPath("$.region").value("GYEONGBUK"))
			.andExpect(jsonPath("$.status").value("ON_SALE"))
			.andExpect(jsonPath("$.minSalePrice").value(32_000))
			.andExpect(jsonPath("$.minPriceListPrice").value(40_000))
			.andExpect(jsonPath("$.discountRate").value(20))
			.andExpect(jsonPath("$.soldOut").value(false))
			.andExpect(jsonPath("$.reviewCount").value(0))
			.andExpect(jsonPath("$.averageRating").value(0.0))
			.andExpect(jsonPath("$.imageUrls").value(contains(BUCKET_URL + "a.jpg", BUCKET_URL + "b.jpg")))
			.andExpect(jsonPath("$.options[*].name").value(contains("3kg 특품", "5kg 특품")))
			.andExpect(jsonPath("$.options[0].optionId").isNumber())
			.andExpect(jsonPath("$.options[0].listPrice").value(40_000))
			.andExpect(jsonPath("$.options[0].salePrice").value(32_000))
			.andExpect(jsonPath("$.options[0].discountRate").value(20))
			.andExpect(jsonPath("$.options[0].soldOut").value(false))
			.andExpect(jsonPath("$.options[1].listPrice").value(49_000))
			.andExpect(jsonPath("$.options[1].discountRate").value(0))
			.andExpect(jsonPath("$.options[1].soldOut").value(true))
			.andExpect(jsonPath("$.createdAt").exists())
			.andExpect(jsonPath("$.updatedAt").exists());
	}

	@Test
	void 상세의_옵션은_가용_재고가_기준_이하일_때만_품절_임박이다() throws Exception {
		// given: 품절 임박 기준은 module-test.yml의 5개다
		Product product = visible("청송 꿀사과", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("기준 이하", 40_000L, 32_000L, 10),
				ProductOptionSpec.newOption("기준 초과", 40_000L, 32_000L, 6),
				ProductOptionSpec.newOption("품절", 40_000L, 32_000L, 0)));
		product.changeStatus(ProductStatus.ON_SALE);
		Long productId = save(product);
		// 총재고가 아니라 주문에 배정하고 남은 가용 재고(10 - 5 = 5)로 판단하는지 본다
		Long optionId = transactionTemplate.execute(
			status -> productRepository.findById(productId).orElseThrow().getOptions().getFirst().getId());
		transactionTemplate.executeWithoutResult(status -> productOptionRepository.allocate(optionId, 5));

		// when & then
		mockMvc.perform(get("/api/v1/products/{productId}", productId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.options[*].name").value(contains("기준 이하", "기준 초과", "품절")))
			.andExpect(jsonPath("$.options[*].lowStock").value(contains(true, false, false)))
			.andExpect(jsonPath("$.options[*].soldOut").value(contains(false, false, true)))
			.andExpect(jsonPath("$.options[0].availableStock").doesNotExist())
			.andExpect(jsonPath("$.options[0].totalStock").doesNotExist());
	}

	@Test
	void 숨기거나_삭제한_상품의_상세는_404() throws Exception {
		// given
		Long hidden = save(product("숨긴 상품", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));
		Product product = visible("삭제한 상품", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10));
		product.delete(LocalDateTime.now());
		Long deleted = save(product);

		// when & then
		mockMvc.perform(get("/api/v1/products/{productId}", hidden))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/products/{productId}", deleted))
			.andExpect(status().isNotFound());
	}

	@Test
	void 상품_카드_여러_개는_요청한_ID_순서로_판매_상태와_품절을_함께_준다() throws Exception {
		// given: 판매 상태와 품절은 거르지 않고 목록처럼 status, soldOut으로 보인다
		Product onSale = visible("청송 꿀사과", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("5kg", 49_000L, 49_000L, 10),
				ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 10)));
		onSale.changeStatus(ProductStatus.ON_SALE);
		Long apple = save(onSale);
		Product paused = visible("제주 당근", ProductCategory.VEGETABLE, ProductRegion.JEJU, option(10));
		paused.changeStatus(ProductStatus.PAUSED);
		Long carrot = save(paused);
		Long soldOut = save(visible("품절 상품", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(0)));

		// when & then: 비로그인 요청이고, 같은 ID를 다시 보내도 한 번만 나온다
		mockMvc.perform(get("/api/v1/products/summaries")
				.param("ids", "%d,%d,%d,%d".formatted(carrot, soldOut, apple, carrot)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].productId")
				.value(contains(carrot.intValue(), soldOut.intValue(), apple.intValue())))
			.andExpect(jsonPath("$[*].status").value(contains("PAUSED", "READY", "ON_SALE")))
			.andExpect(jsonPath("$[*].soldOut").value(contains(false, true, false)))
			.andExpect(jsonPath("$[2].name").value("청송 꿀사과"))
			.andExpect(jsonPath("$[2].thumbnailUrl").value(BUCKET_URL + "a.jpg"))
			.andExpect(jsonPath("$[2].minSalePrice").value(32_000))
			.andExpect(jsonPath("$[2].minPriceListPrice").value(40_000))
			.andExpect(jsonPath("$[2].discountRate").value(20))
			.andExpect(jsonPath("$[2].category").value("FRUIT"))
			.andExpect(jsonPath("$[2].region").value("GYEONGBUK"))
			.andExpect(jsonPath("$[2].reviewCount").value(0))
			.andExpect(jsonPath("$[2].averageRating").value(0.0));
	}

	@Test
	void 상품_카드_여러_개에서_숨긴_상품_삭제된_상품_없는_ID는_빠진다() throws Exception {
		// given
		Long visible = save(visible("청송 꿀사과", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));
		Long hidden = save(product("숨긴 상품", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));
		Product product = visible("삭제한 상품", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10));
		product.delete(LocalDateTime.now());
		Long deleted = save(product);
		long missing = visible + hidden + deleted;

		// when & then
		mockMvc.perform(get("/api/v1/products/summaries")
				.param("ids", "%d,%d,%d,%d".formatted(hidden, deleted, missing, visible)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].productId").value(contains(visible.intValue())));
	}

	@ParameterizedTest
	@MethodSource("잘못된_상품_ID_목록")
	void 상품_카드_여러_개는_ID가_비었거나_100개를_넘거나_숫자가_아니면_400(String ids) throws Exception {
		mockMvc.perform(get("/api/v1/products/summaries").param("ids", ids))
			.andExpect(status().isBadRequest());
	}

	static Stream<String> 잘못된_상품_ID_목록() {
		String tooMany = String.join(",", Stream.iterate(1, i -> i + 1).limit(101).map(String::valueOf).toList());
		return Stream.of("", " ", tooMany, "1,a", "1,,2");
	}

	@Test
	void 상품_카드_여러_개는_ID_100개까지_받는다() throws Exception {
		// given
		Long apple = save(visible("청송 꿀사과", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, option(10)));
		String ids = String.join(",", Stream.iterate(apple, i -> i + 1).limit(100).map(String::valueOf).toList());

		// when & then
		mockMvc.perform(get("/api/v1/products/summaries").param("ids", ids))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].productId").value(contains(apple.intValue())));
	}

	@Test
	void 상품_카드_여러_개의_SQL_수는_상품_수가_늘어도_같다() throws Exception {
		// given
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			ids.add(save(visible("상품" + i, ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
				List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 10),
					ProductOptionSpec.newOption("5kg", 49_000L, 49_000L, 10)))));
		}

		// when
		int one = summaryQueryCount(List.of(ids.getFirst()));
		int three = summaryQueryCount(ids);

		// then: 0이면 세기가 꺼진 것이라 같아도 의미가 없다
		assertThat(one).isPositive();
		assertThat(three).isEqualTo(one);
	}

	private int summaryQueryCount(List<Long> ids) throws Exception {
		String param = String.join(",", ids.stream().map(String::valueOf).toList());
		try (QueryCountContext.Scope scope = QueryCountContext.open()) {
			mockMvc.perform(get("/api/v1/products/summaries").param("ids", param))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(ids.size()));
			return scope.count();
		}
	}

	@Test
	void 카테고리_목록은_상위_카테고리별로_하위_카테고리_코드와_이름을_준다() throws Exception {
		mockMvc.perform(get("/api/v1/products/categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].group").value(contains("AGRICULTURE")))
			.andExpect(jsonPath("$[0].description").value("농산물"))
			.andExpect(jsonPath("$[0].categories.length()").value(ProductCategory.values().length))
			.andExpect(jsonPath("$[0].categories[0].code").value("GRAIN"))
			.andExpect(jsonPath("$[0].categories[0].description").value("쌀·잡곡"));
	}

	private List<Long> productIdsOf(MvcResult result) throws Exception {
		List<Integer> ids = JsonPath.read(result.getResponse().getContentAsString(),
			"$.content[*].productId");
		return ids.stream().map(Long::valueOf).toList();
	}

	private Long save(Product product) {
		return productRepository.save(product).getId();
	}

	private Product visible(String name, ProductCategory category, ProductRegion region,
		List<ProductOptionSpec> options) {
		Product product = product(name, category, region, options);
		product.changeVisibility(ProductVisibility.VISIBLE);
		return product;
	}

	private Product product(String name, ProductCategory category, ProductRegion region,
		List<ProductOptionSpec> options) {
		return Product.create(1L, name, "<p>설명</p>", category, region, options,
			List.of("product/a.jpg", "product/b.jpg"));
	}

	private List<ProductOptionSpec> option(int totalStock) {
		return List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, totalStock));
	}
}
