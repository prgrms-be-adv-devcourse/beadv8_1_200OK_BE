package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

class ProductStatusAndDeleteIntegrationTest extends AbstractIntegrationTest {

	private static final String IMAGE = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg";
	private static final Long SELLER_ID = 1L;
	private static final Long OTHER_SELLER_ID = 2L;

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
	Long productId;
	Long optionId;

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
	void 판매_상태를_바꾼다() throws Exception {
		patchJson("/status", """
			{ "status": "ON_SALE" }
			""")
			.andExpect(status().isNoContent());

		inTransaction(product -> assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE));
	}

	@Test
	void 노출_여부를_바꾼다() throws Exception {
		patchJson("/visibility", """
			{ "visibility": "VISIBLE" }
			""")
			.andExpect(status().isNoContent());

		inTransaction(product -> assertThat(product.getVisibility()).isEqualTo(ProductVisibility.VISIBLE));
	}

	@Test
	void 판매_상태를_빼먹으면_422이고_상태는_그대로다() throws Exception {
		patchJson("/status", "{}")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.type", endsWith("/validation-failed")));

		inTransaction(product -> assertThat(product.getStatus()).isEqualTo(ProductStatus.READY));
	}

	@Test
	void 없는_판매_상태_값이면_400이고_상태는_그대로다() throws Exception {
		patchJson("/status", """
			{ "status": "SOLD" }
			""")
			.andExpect(status().isBadRequest());

		inTransaction(product -> assertThat(product.getStatus()).isEqualTo(ProductStatus.READY));
	}

	@Test
	void 노출_여부를_빼먹으면_422이고_노출은_그대로다() throws Exception {
		patchJson("/visibility", "{}")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.type", endsWith("/validation-failed")));

		inTransaction(product -> assertThat(product.getVisibility()).isEqualTo(ProductVisibility.HIDDEN));
	}

	@Test
	void 다른_판매자의_상품은_상태를_바꿀_수_없다() throws Exception {
		patchJson("/status", OTHER_SELLER_ID, """
			{ "status": "ON_SALE" }
			""")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type", endsWith("/product/product-access-denied")));

		inTransaction(product -> assertThat(product.getStatus()).isEqualTo(ProductStatus.READY));
	}

	@Test
	void 다른_판매자의_상품은_삭제할_수_없다() throws Exception {
		delete(OTHER_SELLER_ID)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type", endsWith("/product/product-access-denied")));

		inTransaction(product -> assertThat(product.isDeleted()).isFalse());
	}

	@Test
	void 삭제하면_상품과_옵션에_삭제_표시가_되고_활성_상품_조회에서_사라지며_재고를_배정할_수_없다() throws Exception {
		delete(SELLER_ID)
			.andExpect(status().isNoContent());

		inTransaction(product -> {
			assertThat(product.isDeleted()).isTrue();
			assertThat(product.getOptions()).allMatch(ProductOption::isDeleted);
		});
		assertThat(productRepository.findByIdAndDeletedAtIsNull(productId)).isEmpty();
		Integer allocated = transactionTemplate.execute(status -> productOptionRepository.allocate(optionId, 1));
		assertThat(allocated).isZero();
	}

	@Test
	void 삭제한_상품은_다시_삭제하거나_수정_상태_노출을_바꿀_수_없다() throws Exception {
		delete(SELLER_ID).andExpect(status().isNoContent());

		delete(SELLER_ID)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type", endsWith("/product/product-not-found")));
		mockMvc.perform(put("/api/v1/products/{productId}", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "version": 0,
					  "name": "청송 꿀사과",
					  "descriptionHtml": "<p>설명</p>",
					  "category": "FRUIT",
					  "region": "GYEONGBUK",
					  "imageUrls": ["%s"],
					  "options": [{ "id": %d, "name": "3kg", "listPrice": 40000, "salePrice": 32000, "totalStock": 100 }]
					}
					""".formatted(IMAGE, optionId)))
			.andExpect(status().isNotFound());
		patchJson("/status", """
			{ "status": "ON_SALE" }
			""")
			.andExpect(status().isNotFound());
		patchJson("/visibility", """
			{ "visibility": "VISIBLE" }
			""")
			.andExpect(status().isNotFound());
	}

	private void inTransaction(Consumer<Product> assertion) {
		transactionTemplate.executeWithoutResult(status ->
			assertion.accept(productRepository.findById(productId).orElseThrow()));
	}

	private ResultActions patchJson(String path, String body) throws Exception {
		return patchJson(path, SELLER_ID, body);
	}

	private ResultActions patchJson(String path, Long memberId, String body) throws Exception {
		return mockMvc.perform(patch("/api/v1/products/{productId}" + path, productId)
			.header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "SELLER"))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions delete(Long memberId) throws Exception {
		return mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/products/{productId}", productId)
			.header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "SELLER")));
	}
}
