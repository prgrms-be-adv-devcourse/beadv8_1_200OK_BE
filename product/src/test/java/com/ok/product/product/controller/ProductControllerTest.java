package com.ok.product.product.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.ok.product.product.domain.Product;
import com.ok.product.product.service.ProductService;

@ActiveProfiles("test")
@WebMvcTest(value = ProductController.class, properties = "app.problem.base-uri=https://api.example.com/problems")
class ProductControllerTest {

	private static final long SELLER_ID = 1L;

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
			.given(productService).update(eq(SELLER_ID), eq(1L), any());

		update()
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.type", endsWith("/product/product-concurrent-modification")));
	}

	@Test
	void 상태_변경과_삭제도_커밋_중_낙관적_락이_실패하면_409를_반환한다() throws Exception {
		willThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L))
			.given(productService).changeStatus(eq(SELLER_ID), eq(1L), any());
		willThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L))
			.given(productService).delete(SELLER_ID, 1L);

		mockMvc.perform(patch("/api/v1/products/{productId}/status", 1L)
				.with(seller())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"status\": \"ON_SALE\" }"))
			.andExpect(status().isConflict());
		mockMvc.perform(delete("/api/v1/products/{productId}", 1L)
				.with(seller()))
			.andExpect(status().isConflict());
	}

	private ResultActions update() throws Exception {
		return mockMvc.perform(put("/api/v1/products/{productId}", 1L)
			.with(seller())
			.contentType(MediaType.APPLICATION_JSON)
			.content(UPDATE_BODY));
	}

	// 409 변환만 보는 슬라이스라 판매자 인증 정보를 직접 넣는다. 인증·인가는 ProductSellerAuthIntegrationTest가 실제 토큰으로 검증한다
	private RequestPostProcessor seller() {
		return jwt()
			.jwt(token -> token.subject(String.valueOf(SELLER_ID)))
			.authorities(new SimpleGrantedAuthority("ROLE_SELLER"));
	}
}
