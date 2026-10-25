package com.ok.product;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.testsupport.AbstractIntegrationTest;

@AutoConfigureMockMvc
class BuyerProductIntegrationTest extends AbstractIntegrationTest {

	private static final String BUCKET_URL = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductRepository productRepository;

	@Autowired
	ProductOptionRepository productOptionRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	// 목록 테스트는 다른 테스트가 남긴 상품이 섞이면 깨지므로 시작 전에 비운다
	@BeforeEach
	void setUp() {
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
	void 없는_필터_값이나_범위를_벗어난_페이지는_400(String name, String value) throws Exception {
		mockMvc.perform(get("/api/v1/products").param(name, value))
			.andExpect(status().isBadRequest());
	}

	static Stream<Arguments> 잘못된_목록_조회_파라미터() {
		return Stream.of(
			Arguments.of("category", "APPLE"),
			Arguments.of("page", "-1"),
			Arguments.of("size", "0"),
			Arguments.of("size", "101"));
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
	void 카테고리_목록은_상위_카테고리별로_하위_카테고리_코드와_이름을_준다() throws Exception {
		mockMvc.perform(get("/api/v1/products/categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].group").value(contains("AGRICULTURE")))
			.andExpect(jsonPath("$[0].description").value("농산물"))
			.andExpect(jsonPath("$[0].categories.length()").value(ProductCategory.values().length))
			.andExpect(jsonPath("$[0].categories[0].code").value("GRAIN"))
			.andExpect(jsonPath("$[0].categories[0].description").value("쌀·잡곡"));
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
