package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductImage;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.product.service.ProductService;
import com.ok.product.product.service.dto.ProductStatusCode;
import com.ok.testsupport.TestcontainersConfiguration;

// 커밋 시점에 version이 오르는 것까지 보려고 테스트 메서드에 @Transactional을 붙이지 않는다
@ActiveProfiles("test")
@SpringBootTest(properties = {
	"app.problem.base-uri=https://api.example.com/problems"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductUpdateIntegrationTest {

	// application-test.yml의 버킷(test-mock-bucket)과 리전으로 만든 업로드 URL
	private static final String IMAGE = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg";
	private static final String IMAGE_B = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/b.jpg";
	// 위 URL로 요청하면 DB에는 objectKey만 저장된다
	private static final String IMAGE_KEY = "product/a.jpg";
	private static final String IMAGE_B_KEY = "product/b.jpg";
	private static final Long SELLER_ID = 1L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductRepository productRepository;

	@Autowired
	ProductOptionRepository productOptionRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	ProductService productService;

	Long productId;
	Long optionId;

	@BeforeEach
	void setUp() {
		Product product = productRepository.save(Product.create(SELLER_ID, "청송 꿀사과", "<p>설명</p>",
			ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 100)), List.of(IMAGE_KEY)));
		productId = product.getId();
		optionId = product.getOptions().getFirst().getId();
	}

	@AfterEach
	void tearDown() {
		productRepository.deleteAll();
	}

	@Test
	void 수정하면_기존_옵션_id가_유지되고_새_옵션이_추가되며_version이_오른다() throws Exception {
		String options = """
			[
			  { "id": %d, "name": "3kg 특품", "listPrice": 40000, "salePrice": 30000, "totalStock": 150 },
			  { "name": "5kg", "salePrice": 49000, "totalStock": 20 }
			]
			""".formatted(optionId);

		update(SELLER_ID, body(0, "청송 꿀사과 (수정)", IMAGE_B, options))
			.andExpect(status().isNoContent());

		inTransaction(product -> {
			// 상품 행이 바뀌면 @Version이 한 번, OPTIMISTIC_FORCE_INCREMENT가 커밋 때 한 번 더 올려서 1보다 클 수 있다
			assertThat(product.getVersion()).isPositive();
			assertThat(product.getName()).isEqualTo("청송 꿀사과 (수정)");
			assertThat(product.getDescriptionHtml()).isEqualTo("<p>수정한 설명</p>");
			assertThat(product.getImages()).extracting(ProductImage::getObjectKey).containsExactly(IMAGE_B_KEY);
			assertThat(product.getThumbnailKey()).isEqualTo(IMAGE_B_KEY);

			List<ProductOption> active = product.activeOptions();
			assertThat(active).extracting(ProductOption::getName, ProductOption::getListPrice,
					ProductOption::getSalePrice, ProductOption::getTotalStock, ProductOption::getSortOrder)
				.containsExactly(
					tuple("3kg 특품", 40_000L, 30_000L, 150, 0),
					tuple("5kg", 49_000L, 49_000L, 20, 1));
			assertThat(active.getFirst().getId()).isEqualTo(optionId);
			assertThat(active.get(1).getId()).isNotNull().isNotEqualTo(optionId);
			assertThat(product.getMinSalePrice()).isEqualTo(30_000L);
			assertThat(product.getMinPriceListPrice()).isEqualTo(40_000L);
		});
	}

	@Test
	void 판매_상태와_노출을_보내면_바뀌고_생략하면_그대로다() throws Exception {
		String withStatus = body(0, sameOption(100)).replace("\"region\": \"GYEONGBUK\",",
			"\"region\": \"GYEONGBUK\", \"status\": \"ON_SALE\", \"visibility\": \"VISIBLE\",");
		update(SELLER_ID, withStatus)
			.andExpect(status().isNoContent());

		Integer version = transactionTemplate.execute(status -> productRepository.findById(productId).orElseThrow().getVersion());
		update(SELLER_ID, body(version, sameOption(100)))
			.andExpect(status().isNoContent());

		inTransaction(product -> {
			assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			assertThat(product.getVisibility()).isEqualTo(ProductVisibility.VISIBLE);
		});
	}

	@Test
	void 요청에_없는_기존_옵션은_삭제된다() throws Exception {
		String options = """
			[{ "name": "10kg", "salePrice": 70000, "totalStock": 5 }]
			""";

		update(SELLER_ID, body(0, options))
			.andExpect(status().isNoContent());

		inTransaction(product -> {
			assertThat(product.activeOptions()).extracting(ProductOption::getName).containsExactly("10kg");
			assertThat(product.getOptions()).filteredOn(ProductOption::isDeleted)
				.extracting(ProductOption::getId).containsExactly(optionId);
		});
	}

	@Test
	void 다른_판매자는_수정할_수_없다() throws Exception {
		update(2L, body(0, sameOption(100)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type", endsWith("/product/product-access-denied")));
	}

	@Test
	void 오래된_version으로_수정하면_409이고_먼저_커밋된_수정이_남는다() throws Exception {
		update(SELLER_ID, body(0, "첫 번째 수정", IMAGE, sameOption(100))).andExpect(status().isNoContent());

		String staleOption = """
			[{ "id": %d, "name": "3kg", "listPrice": 40000, "salePrice": 25000, "totalStock": 100 }]
			""".formatted(optionId);
		update(SELLER_ID, body(0, "두 번째 수정", IMAGE_B, staleOption))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.type", endsWith("/product/product-concurrent-modification")));

		inTransaction(product -> {
			assertThat(product.getName()).isEqualTo("첫 번째 수정");
			assertThat(product.getThumbnailKey()).isEqualTo(IMAGE_KEY);
			assertThat(product.activeOptions().getFirst().getSalePrice()).isEqualTo(32_000L);
		});
	}

	@Test
	void 배정된_수량보다_총재고를_적게_줄이면_422이고_전부_롤백된다() throws Exception {
		transactionTemplate.executeWithoutResult(status -> productOptionRepository.allocate(optionId, 5));

		// 정보·이미지·가격·새 옵션을 함께 바꾸지만 기존 옵션의 재고를 배정 수량(5)보다 적게 줄인다
		String options = """
			[
			  { "id": %d, "name": "3kg", "listPrice": 40000, "salePrice": 25000, "totalStock": 3 },
			  { "name": "5kg", "salePrice": 49000, "totalStock": 20 }
			]
			""".formatted(optionId);
		update(SELLER_ID, body(0, "바뀌면 안 되는 이름", IMAGE_B, options))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.type", endsWith("/product/stock-below-allocated")));

		inTransaction(product -> {
			assertThat(product.getVersion()).isZero();
			assertThat(product.getName()).isEqualTo("청송 꿀사과");
			assertThat(product.getImages()).extracting(ProductImage::getObjectKey).containsExactly(IMAGE_KEY);
			assertThat(product.getThumbnailKey()).isEqualTo(IMAGE_KEY);
			assertThat(product.getOptions()).hasSize(1);
			ProductOption option = product.getOptions().getFirst();
			assertThat(option.getSalePrice()).isEqualTo(32_000L);
			assertThat(option.getTotalStock()).isEqualTo(100);
			assertThat(option.getAllocatedQuantity()).isEqualTo(5);
			assertThat(product.getMinSalePrice()).isEqualTo(32_000L);
		});
	}

	@Test
	void 읽은_뒤_커밋_전에_다른_수정이_먼저_커밋되면_커밋할_때_낙관적_락_예외가_난다() {
		// 컨트롤러는 이 예외를 409로 바꾼다 (ProductControllerTest). 실제 DB에서도 이 타입으로 나오는지 확인한다
		TransactionTemplate other = new TransactionTemplate(transactionManager);
		other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			productService.changeStatus(SELLER_ID, productId, ProductStatusCode.ON_SALE); // version 0으로 읽음
			other.executeWithoutResult(s ->
				productRepository.findById(productId).orElseThrow().changeVisibility(ProductVisibility.VISIBLE));
		})).isInstanceOf(OptimisticLockingFailureException.class);

		inTransaction(product -> {
			assertThat(product.getStatus()).isEqualTo(ProductStatus.READY);
			assertThat(product.getVisibility()).isEqualTo(ProductVisibility.VISIBLE);
		});
	}

	private void inTransaction(Consumer<Product> assertion) {
		transactionTemplate.executeWithoutResult(status ->
			assertion.accept(productRepository.findById(productId).orElseThrow()));
	}

	private ResultActions update(Long memberId, String body) throws Exception {
		return mockMvc.perform(put("/api/v1/products/{productId}", productId)
			.header("X-Member-Id", memberId)
			.header("X-Member-Role", "SELLER")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private String sameOption(int totalStock) {
		return """
			[{ "id": %d, "name": "3kg", "listPrice": 40000, "salePrice": 32000, "totalStock": %d }]
			""".formatted(optionId, totalStock);
	}

	private String body(int version, String options) {
		return body(version, "청송 꿀사과 (수정)", IMAGE, options);
	}

	private String body(int version, String name, String imageUrl, String options) {
		return """
			{
			  "version": %d,
			  "name": "%s",
			  "descriptionHtml": "<p>수정한 설명</p>",
			  "category": "FRUIT",
			  "region": "GYEONGBUK",
			  "imageUrls": ["%s"],
			  "options": %s
			}
			""".formatted(version, name, imageUrl, options);
	}
}
