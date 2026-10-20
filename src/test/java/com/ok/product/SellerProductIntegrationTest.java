package com.ok.product;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

class SellerProductIntegrationTest extends AbstractIntegrationTest {

	private static final String URL = "/api/v1/sellers/me/products";
	private static final String BUCKET_URL = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/";
	private static final Long SELLER_ID = 1L;
	private static final Long OTHER_SELLER_ID = 2L;
	private static final Long BUYER_ID = 3L;

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	@Autowired
	ProductRepository productRepository;

	@Autowired
	ProductOptionRepository productOptionRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	private MockMvc mockMvc;

	// 목록 테스트는 다른 테스트가 남긴 상품이 섞이면 깨지므로 시작 전에 비운다
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		productRepository.deleteAll();
	}

	@Test
	void 목록에는_본인_상품만_최신순으로_나오고_숨긴_상품도_포함된다() throws Exception {
		// given
		Product apple = product(SELLER_ID, "청송 꿀사과",
			List.of(ProductOptionSpec.newOption("5kg", 49_000L, 49_000L, 10),
				ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 10)));
		apple.changeStatus(ProductStatus.ON_SALE);
		apple.changeVisibility(ProductVisibility.VISIBLE);
		Long appleId = save(apple);
		Long allocatedOptionId = transactionTemplate.execute(
			status -> productRepository.findById(appleId).orElseThrow().activeOptions().getFirst().getId());
		transactionTemplate.executeWithoutResult(status -> productOptionRepository.allocate(allocatedOptionId, 3));
		Long hiddenId = save(product(SELLER_ID, "숨긴 상품", option(10)));
		Product deleted = product(SELLER_ID, "삭제한 상품", option(10));
		deleted.delete(LocalDateTime.now());
		save(deleted);
		save(product(OTHER_SELLER_ID, "다른 판매자 상품", option(10)));

		// when & then
		mockMvc.perform(asSeller(get(URL)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].productId").value(contains(hiddenId.intValue(), appleId.intValue())))
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalPages").value(1))
			.andExpect(jsonPath("$.content[0].visibility").value("HIDDEN"))
			.andExpect(jsonPath("$.content[1].name").value("청송 꿀사과"))
			.andExpect(jsonPath("$.content[1].thumbnailUrl").value(BUCKET_URL + "a.jpg"))
			.andExpect(jsonPath("$.content[1].minSalePrice").value(32_000))
			.andExpect(jsonPath("$.content[1].minPriceListPrice").value(40_000))
			.andExpect(jsonPath("$.content[1].discountRate").value(20))
			.andExpect(jsonPath("$.content[1].category").value("FRUIT"))
			.andExpect(jsonPath("$.content[1].region").value("GYEONGBUK"))
			.andExpect(jsonPath("$.content[1].status").value("ON_SALE"))
			.andExpect(jsonPath("$.content[1].visibility").value("VISIBLE"))
			.andExpect(jsonPath("$.content[1].availableStock").value(17))
			.andExpect(jsonPath("$.content[1].soldOut").value(false))
			.andExpect(jsonPath("$.content[1].reviewCount").value(0))
			.andExpect(jsonPath("$.content[1].averageRating").value(0.0))
			.andExpect(jsonPath("$.content[1].createdAt").exists())
			.andExpect(jsonPath("$.content[1].updatedAt").exists());
	}

	@Test
	void 판매_상태와_노출로_거른다() throws Exception {
		// given
		Product onSaleVisible = product(SELLER_ID, "판매 중·노출", option(10));
		onSaleVisible.changeStatus(ProductStatus.ON_SALE);
		onSaleVisible.changeVisibility(ProductVisibility.VISIBLE);
		Long onSaleVisibleId = save(onSaleVisible);
		Product onSaleHidden = product(SELLER_ID, "판매 중·숨김", option(10));
		onSaleHidden.changeStatus(ProductStatus.ON_SALE);
		Long onSaleHiddenId = save(onSaleHidden);
		Long readyHiddenId = save(product(SELLER_ID, "판매 준비·숨김", option(10)));

		// when & then
		mockMvc.perform(asSeller(get(URL).param("status", "ON_SALE")))
			.andExpect(jsonPath("$.content[*].productId")
				.value(contains(onSaleHiddenId.intValue(), onSaleVisibleId.intValue())));
		mockMvc.perform(asSeller(get(URL).param("visibility", "HIDDEN")))
			.andExpect(jsonPath("$.content[*].productId")
				.value(contains(readyHiddenId.intValue(), onSaleHiddenId.intValue())));
		mockMvc.perform(asSeller(get(URL).param("status", "ON_SALE").param("visibility", "HIDDEN")))
			.andExpect(jsonPath("$.content[*].productId").value(contains(onSaleHiddenId.intValue())));
	}

	@Test
	void 페이지_크기만큼_나누고_전체_개수를_알려준다() throws Exception {
		// given
		Long first = save(product(SELLER_ID, "첫 번째", option(10)));
		save(product(SELLER_ID, "두 번째", option(10)));
		save(product(SELLER_ID, "세 번째", option(10)));

		// when & then
		mockMvc.perform(asSeller(get(URL).param("page", "1").param("size", "2")))
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
		mockMvc.perform(asSeller(get(URL).param(name, value)))
			.andExpect(status().isBadRequest());
	}

	static Stream<Arguments> 잘못된_목록_조회_파라미터() {
		return Stream.of(
			Arguments.of("status", "SOLD_OUT"),
			Arguments.of("visibility", "PUBLIC"),
			Arguments.of("page", "-1"),
			Arguments.of("size", "0"),
			Arguments.of("size", "101"));
	}

	@Test
	void 토큰_없이_호출하면_401이다() throws Exception {
		mockMvc.perform(get(URL))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(jsonPath("$.type", endsWith("/unauthorized")));
	}

	@Test
	void SELLER가_아닌_회원이_호출하면_403이다() throws Exception {
		mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type", endsWith("/forbidden")));
	}

	private MockHttpServletRequestBuilder asSeller(MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER"));
	}

	private Long save(Product product) {
		return productRepository.save(product).getId();
	}

	private Product product(Long sellerId, String name, List<ProductOptionSpec> options) {
		return Product.create(sellerId, name, "<p>설명</p>", ProductCategory.FRUIT, ProductRegion.GYEONGBUK, options,
			List.of("product/a.jpg", "product/b.jpg"));
	}

	private List<ProductOptionSpec> option(int totalStock) {
		return List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, totalStock));
	}
}
