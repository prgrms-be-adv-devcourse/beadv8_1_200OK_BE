package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import com.ok.product.product.repository.ProductRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

// 판매자 상품 API는 컨트롤러 클래스 단위로 SELLER만 허용한다.
class ProductSellerAuthIntegrationTest extends AbstractIntegrationTest {

	private static final String IMAGE = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg";
	private static final Long SELLER_ID = 1L;
	private static final Long BUYER_ID = 2L;

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	@Autowired
	ProductRepository productRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	private MockMvc mockMvc;
	private Long productId;
	private Long optionId;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

		Product product = productRepository.save(Product.create(SELLER_ID, "청송 꿀사과", "<p>설명</p>",
			ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 100)), List.of("product/a.jpg")));
		productId = product.getId();
		optionId = product.getOptions().getFirst().getId();
	}

	@AfterEach
	void tearDown() {
		productRepository.deleteAll();
	}

	@Test
	void 토큰_없이_판매자_상품_API를_호출하면_401이고_상품은_바뀌지_않는다() throws Exception {
		// given
		long productCount = productRepository.count();

		// when & then
		for (MockHttpServletRequestBuilder request : sellerRequests()) {
			mockMvc.perform(request)
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(jsonPath("$.type", endsWith("/unauthorized")));
		}
		assertUnchanged(productCount);
	}

	@Test
	void SELLER가_아닌_회원이_판매자_상품_API를_호출하면_403이고_상품은_바뀌지_않는다() throws Exception {
		// given
		long productCount = productRepository.count();
		String buyerToken = tokens.bearer(BUYER_ID, "BUYER");

		// when & then
		for (MockHttpServletRequestBuilder request : sellerRequests()) {
			mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, buyerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type", endsWith("/forbidden")));
		}
		assertUnchanged(productCount);
	}

	// 요청 본문 검증(@Valid)이 인가 검사보다 먼저 일어나므로, 인가에서 막히는지 보려면 본문은 유효해야 한다
	private List<MockHttpServletRequestBuilder> sellerRequests() {
		return List.of(
			post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content("""
				{
				  "name": "새 상품",
				  "descriptionHtml": "<p>설명</p>",
				  "category": "FRUIT",
				  "region": "GYEONGBUK",
				  "imageUrls": ["%s"],
				  "options": [{ "name": "3kg", "listPrice": 40000, "salePrice": 32000, "totalStock": 100 }]
				}
				""".formatted(IMAGE)),
			put("/api/v1/products/{productId}", productId).contentType(MediaType.APPLICATION_JSON).content("""
				{
				  "version": 0,
				  "name": "바뀌면 안 되는 이름",
				  "descriptionHtml": "<p>설명</p>",
				  "category": "FRUIT",
				  "region": "GYEONGBUK",
				  "imageUrls": ["%s"],
				  "options": [{ "id": %d, "name": "3kg", "listPrice": 40000, "salePrice": 32000, "totalStock": 100 }]
				}
				""".formatted(IMAGE, optionId)),
			patch("/api/v1/products/{productId}/status", productId).contentType(MediaType.APPLICATION_JSON)
				.content("{ \"status\": \"ON_SALE\" }"),
			patch("/api/v1/products/{productId}/visibility", productId).contentType(MediaType.APPLICATION_JSON)
				.content("{ \"visibility\": \"VISIBLE\" }"),
			delete("/api/v1/products/{productId}", productId));
	}

	private void assertUnchanged(long productCount) {
		assertThat(productRepository.count()).isEqualTo(productCount);
		transactionTemplate.executeWithoutResult(status -> {
			Product product = productRepository.findById(productId).orElseThrow();
			assertThat(product).isNotNull();
			assertSoftly(softly -> {
				softly.assertThat(product.getName()).isEqualTo("청송 꿀사과");
				softly.assertThat(product.getStatus()).isEqualTo(ProductStatus.READY);
				softly.assertThat(product.getVisibility()).isEqualTo(ProductVisibility.HIDDEN);
				softly.assertThat(product.isDeleted()).isFalse();
			});
		});
	}
}
