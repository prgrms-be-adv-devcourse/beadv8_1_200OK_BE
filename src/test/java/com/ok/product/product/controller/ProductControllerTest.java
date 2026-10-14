package com.ok.product.product.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ok.product.product.domain.Product;
import com.ok.product.product.service.ProductService;

@ActiveProfiles("test")
@WebMvcTest(value = ProductController.class, properties = "app.problem.base-uri=https://api.example.com/problems")
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerTest {

	private static final String CREATE_BODY = """
		{
		  "name": "청송 꿀사과",
		  "descriptionHtml": "<p>설명</p>",
		  "category": "FRUIT",
		  "region": "GYEONGBUK",
		  "imageUrls": ["https://bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg"],
		  "options": [{ "name": "3kg", "listPrice": 40000, "salePrice": 32000, "totalStock": 100 }]
		}
		""";

	private static final String UPDATE_BODY = """
		{
		  "version": 0,
		  "name": "청송 꿀사과",
		  "descriptionHtml": "<p>설명</p>",
		  "category": "FRUIT",
		  "region": "GYEONGBUK",
		  "imageUrls": ["https://bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg"],
		  "options": [{ "id": 11, "name": "3kg", "listPrice": 40000, "salePrice": 32000, "totalStock": 100 }]
		}
		""";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ProductService productService;

	@Test
	void 수정_커밋_중_낙관적_락이_실패하면_409를_반환한다() throws Exception {
		willThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L))
			.given(productService).update(eq(1L), eq(1L), any());

		update("SELLER")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.type", endsWith("/product/product-concurrent-modification")));
	}

	@Test
	void SELLER가_아니면_등록_수정_상태_변경_삭제를_할_수_없다() throws Exception {
		List<MockHttpServletRequestBuilder> requests = List.of(
			post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY),
			put("/api/v1/products/{productId}", 1L).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY),
			patch("/api/v1/products/{productId}/status", 1L).contentType(MediaType.APPLICATION_JSON)
				.content("{ \"status\": \"ON_SALE\" }"),
			patch("/api/v1/products/{productId}/visibility", 1L).contentType(MediaType.APPLICATION_JSON)
				.content("{ \"visibility\": \"VISIBLE\" }"),
			delete("/api/v1/products/{productId}", 1L));

		for (MockHttpServletRequestBuilder request : requests) {
			mockMvc.perform(request
					.header(ProductController.MEMBER_ID_HEADER, 1L)
					.header(ProductController.MEMBER_ROLE_HEADER, "BUYER"))
				.andExpect(status().isForbidden());
		}
		then(productService).shouldHaveNoInteractions();
	}

	@Test
	void 상태_변경과_삭제도_커밋_중_낙관적_락이_실패하면_409를_반환한다() throws Exception {
		willThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L))
			.given(productService).changeStatus(eq(1L), eq(1L), any());
		willThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L))
			.given(productService).delete(1L, 1L);

		mockMvc.perform(patch("/api/v1/products/{productId}/status", 1L)
				.header(ProductController.MEMBER_ID_HEADER, 1L)
				.header(ProductController.MEMBER_ROLE_HEADER, "SELLER")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"status\": \"ON_SALE\" }"))
			.andExpect(status().isConflict());
		mockMvc.perform(delete("/api/v1/products/{productId}", 1L)
				.header(ProductController.MEMBER_ID_HEADER, 1L)
				.header(ProductController.MEMBER_ROLE_HEADER, "SELLER"))
			.andExpect(status().isConflict());
	}

	private ResultActions update(String role) throws Exception {
		return mockMvc.perform(put("/api/v1/products/{productId}", 1L)
			.header(ProductController.MEMBER_ID_HEADER, 1L)
			.header(ProductController.MEMBER_ROLE_HEADER, role)
			.contentType(MediaType.APPLICATION_JSON)
			.content(UPDATE_BODY));
	}
}
