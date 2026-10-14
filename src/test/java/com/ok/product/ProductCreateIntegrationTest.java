package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductImage;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.repository.ProductRepository;
import com.ok.testsupport.TestcontainersConfiguration;

import com.jayway.jsonpath.JsonPath;

@ActiveProfiles("test")
@SpringBootTest(properties = {
	"app.problem.base-uri=https://api.example.com/problems"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductCreateIntegrationTest {

	// application-test.yml의 버킷(test-mock-bucket)과 리전으로 만든 업로드 URL
	private static final String BUCKET_URL = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/";
	private static final String IMAGE = BUCKET_URL + "a.jpg";
	private static final String OPTION = """
		{ "name": "3kg", "listPrice": 40000, "salePrice": 32000, "totalStock": 10 }""";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductRepository productRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	@AfterEach
	void tearDown() {
		productRepository.deleteAll();
	}

	@Test
	void 등록하면_201과_상품_ID를_반환하고_요청값이_그대로_저장된다() throws Exception {
		String body = """
			{
			  "name": "청송 꿀사과",
			  "descriptionHtml": "<p>청송 사과입니다</p><script>alert(1)</script>",
			  "category": "FRUIT",
			  "region": "GYEONGBUK",
			  "imageUrls": ["%s", "%s"],
			  "options": [
			    { "name": "3kg 특품", "listPrice": 40000, "salePrice": 32000, "totalStock": 100 },
			    { "name": "5kg 특품", "salePrice": 49000, "totalStock": 50 }
			  ]
			}
			""".formatted(IMAGE, BUCKET_URL + "b.jpg");

		String response = create(body)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.productId").isNumber())
			.andReturn().getResponse().getContentAsString();

		Long productId = ((Number)JsonPath.read(response, "$.productId")).longValue();
		// 커밋된 뒤 새 트랜잭션에서 DB 값을 읽는다
		transactionTemplate.executeWithoutResult(status -> {
			Product product = productRepository.findById(productId).orElseThrow();
			assertThat(product.getSellerId()).isEqualTo(1L);
			assertThat(product.getName()).isEqualTo("청송 꿀사과");
			assertThat(product.getCategory()).isEqualTo(ProductCategory.FRUIT);
			assertThat(product.getRegion()).isEqualTo(ProductRegion.GYEONGBUK);
			assertThat(product.getStatus()).isEqualTo(ProductStatus.READY);
			assertThat(product.getVisibility()).isEqualTo(ProductVisibility.HIDDEN);
			assertThat(product.getDescriptionHtml()).isEqualTo("<p>청송 사과입니다</p>");
			// 정가를 생략한 두 번째 옵션은 판매가로 채워진다
			assertThat(product.activeOptions())
				.extracting(ProductOption::getName, ProductOption::getListPrice, ProductOption::getSalePrice,
					ProductOption::getTotalStock, ProductOption::getSortOrder)
				.containsExactly(
					tuple("3kg 특품", 40_000L, 32_000L, 100, 0),
					tuple("5kg 특품", 49_000L, 49_000L, 50, 1));
			assertThat(product.getImages()).extracting(ProductImage::getUrl, ProductImage::getSortOrder)
				.containsExactly(tuple(IMAGE, 0), tuple(BUCKET_URL + "b.jpg", 1));
			assertThat(product.getThumbnailUrl()).isEqualTo(IMAGE);
			assertThat(product.getMinSalePrice()).isEqualTo(32_000L);
			assertThat(product.getMinPriceListPrice()).isEqualTo(40_000L);
		});
	}

	@Test
	void 이미지_10장과_옵션_50개까지_등록할_수_있다() throws Exception {
		create(body(images(10), options(50)))
			.andExpect(status().isCreated());
	}

	@Test
	void 이미지가_11장이면_422() throws Exception {
		create(body(images(11), "[" + OPTION + "]"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.type", endsWith("/validation-failed")))
			.andExpect(jsonPath("$.errors[0].path").value("imageUrls"));
	}

	@Test
	void 옵션이_없거나_51개면_422() throws Exception {
		create(body(images(1), "[]"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errors[0].path").value("options"));
		create(body(images(1), options(51)))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errors[0].path").value("options"));
	}

	@Test
	void 옵션_안의_판매가_누락과_음수_재고도_검증한다() throws Exception {
		create(body(images(1), """
			[{ "name": "3kg", "totalStock": 10 }]"""))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errors[0].path").value("options[0].salePrice"));
		create(body(images(1), """
			[{ "name": "3kg", "salePrice": 32000, "totalStock": -1 }]"""))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errors[0].path").value("options[0].totalStock"));
	}

	@Test
	void 상세설명_원문이_너무_길면_정제하기_전에_422() throws Exception {
		String longDescription = "<p>" + "가".repeat(200_000) + "</p>";

		create(body(images(1), "[" + OPTION + "]").replace("<p>설명</p>", longDescription))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errors[0].path").value("descriptionHtml"));
	}

	@Test
	void 옵션명이_겹치면_409이고_저장되지_않는다() throws Exception {
		create(body(images(1), "[" + OPTION + ", " + OPTION + "]"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.type", endsWith("/product/duplicate-option-name")));

		assertThat(productRepository.count()).isZero();
	}

	@Test
	void 판매가가_정가보다_크면_422이고_저장되지_않는다() throws Exception {
		create(body(images(1), """
			[{ "name": "3kg", "listPrice": 30000, "salePrice": 35000, "totalStock": 10 }]"""))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.type", endsWith("/product/price-exceeds-list-price")));

		assertThat(productRepository.count()).isZero();
	}

	@Test
	void 우리_버킷이_아닌_이미지_URL이면_422이고_저장되지_않는다() throws Exception {
		create(body(List.of("https://evil.com/a.jpg"), "[" + OPTION + "]"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.type", endsWith("/product/invalid-image-url")));

		assertThat(productRepository.count()).isZero();
	}

	private ResultActions create(String body) throws Exception {
		return mockMvc.perform(post("/api/v1/products")
			.header("X-Member-Id", 1L)
			.header("X-Member-Role", "SELLER")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private String body(List<String> imageUrls, String options) {
		String images = imageUrls.stream().map(url -> "\"" + url + "\"").collect(Collectors.joining(", "));
		return """
			{
			  "name": "청송 꿀사과",
			  "descriptionHtml": "<p>설명</p>",
			  "category": "FRUIT",
			  "region": "GYEONGBUK",
			  "imageUrls": [%s],
			  "options": %s
			}
			""".formatted(images, options);
	}

	private List<String> images(int count) {
		return IntStream.range(0, count).mapToObj(i -> BUCKET_URL + i + ".jpg").toList();
	}

	private String options(int count) {
		return IntStream.range(0, count)
			.mapToObj(i -> "{ \"name\": \"옵션%d\", \"salePrice\": 10000, \"totalStock\": 1 }".formatted(i))
			.collect(Collectors.joining(", ", "[", "]"));
	}
}
