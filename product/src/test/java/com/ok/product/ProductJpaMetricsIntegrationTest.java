package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.ok.common.jpa.QueryCountContext;
import com.ok.common.jpa.QueryCountFilter;
import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJudgmentJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductSearchJpaRepository;
import com.ok.product.product.application.dto.ProductStatusCode;
import com.ok.product.product.application.service.ProductService;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

// JPA 관측 지표(#509)가 실제 앱 설정에서 노출되는지 확인한다. 커밋 시점의 충돌까지 보려고 @Transactional을 붙이지 않는다
class ProductJpaMetricsIntegrationTest extends AbstractIntegrationTest {

	private static final Long SELLER_ID = 1L;
	private static final String IMAGE_KEY = "product/a.jpg";

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	@Autowired
	MeterRegistry meterRegistry;

	@PersistenceContext
	EntityManager entityManager;

	@Autowired
	ProductJpaRepository productRepository;

	@Autowired
	ProductTagJpaRepository productTagRepository;

	@Autowired
	ProductTagJudgmentJpaRepository productTagJudgmentRepository;

	@Autowired
	ProductSearchJpaRepository productSearchRepository;

	@Autowired
	ProductService productService;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	FilterRegistrationBean<QueryCountFilter> queryCountFilterRegistration;

	Long productId;

	@BeforeEach
	void setUp() {
		Product product = productRepository.save(Product.create(SELLER_ID, "청송 꿀사과", "<p>설명</p>",
			ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 100)), List.of(IMAGE_KEY)));
		productId = product.getId();
	}

	@AfterEach
	void tearDown() {
		// 검색 행이 상품을 외래키로 가리키므로 먼저 지운다
		productSearchRepository.deleteAll();
		// 태그와 태그 판정 행이 상품을 외래키로 가리키므로 먼저 지운다
		productTagRepository.deleteAllInBatch();
		productTagJudgmentRepository.deleteAllInBatch();
		productRepository.deleteAll();
	}

	@Test
	void 커밋할_때_버전_증가가_충돌하면_낙관적_락_실패_지표가_엔티티_태그로_오른다() {
		// given: 상품 상태 변경은 커밋 때 버전을 올린다(OPTIMISTIC_FORCE_INCREMENT)
		double before = optimisticLockFailures();
		TransactionTemplate other = new TransactionTemplate(transactionManager);
		other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		// when
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			productService.changeStatus(SELLER_ID, productId, ProductStatusCode.ON_SALE); // version 0으로 읽음
			other.executeWithoutResult(s ->
				productRepository.findById(productId).orElseThrow().changeVisibility(ProductVisibility.VISIBLE));
		})).isInstanceOf(OptimisticLockingFailureException.class);

		// then
		assertThat(optimisticLockFailures()).isEqualTo(before + 1);
	}

	@Test
	void 일반_UPDATE가_충돌해도_낙관적_락_실패_지표가_오른다() {
		// given
		double before = optimisticLockFailures();
		TransactionTemplate other = new TransactionTemplate(transactionManager);
		other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		// when
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			productRepository.findById(productId).orElseThrow().changeVisibility(ProductVisibility.VISIBLE); // version 0으로 읽고 수정
			other.executeWithoutResult(s ->
				productRepository.findById(productId).orElseThrow().changeVisibility(ProductVisibility.VISIBLE));
		})).isInstanceOf(OptimisticLockingFailureException.class);

		// then
		assertThat(optimisticLockFailures()).isEqualTo(before + 1);
	}

	@Test
	void 요청이_실행한_SQL_수가_메서드와_템플릿_URI별_지표로_남는다() throws Exception {
		// given: 컨트롤러까지 가는 요청에 쿼리 수 필터를 붙인다
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(queryCountFilterRegistration.getFilter())
			.build();
		String body = """
			{
			  "version": 0,
			  "name": "청송 꿀사과 (수정)",
			  "descriptionHtml": "<p>수정한 설명</p>",
			  "category": "FRUIT",
			  "region": "GYEONGBUK",
			  "imageUrls": ["https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg"],
			  "options": [{ "id": %d, "name": "3kg", "listPrice": 40000, "salePrice": 32000, "totalStock": 100 }]
			}
			""".formatted(optionId());

		// when
		mockMvc.perform(put("/api/v1/products/{productId}", productId)
				.header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isNoContent());

		// then
		DistributionSummary sut = meterRegistry.find("jpa.queries.per.request")
			.tag("method", "PUT").tag("uri", "/api/v1/products/{productId}").summary();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.count()).isEqualTo(1);
			softly.assertThat(sut.totalAmount()).isPositive();
		});
	}

	// repository 호출(예외 번역 프록시)과 바깥 트랜잭션(커밋 번역)이 모두 같은 예외를 지나가도 한 번만 세는지 본다
	@Test
	void 중첩_트랜잭션_안의_repository_flush에서_난_충돌은_한_번만_센다() {
		// given
		double before = optimisticLockFailures();
		TransactionTemplate other = new TransactionTemplate(transactionManager);
		other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		// when
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(outer ->
			transactionTemplate.executeWithoutResult(inner -> {
				Product product = productRepository.findById(productId).orElseThrow();
				product.changeVisibility(ProductVisibility.VISIBLE); // version 0으로 읽고 수정
				other.executeWithoutResult(s ->
					productRepository.findById(productId).orElseThrow().changeVisibility(ProductVisibility.VISIBLE));
				productRepository.saveAndFlush(product); // repository 프록시에서 충돌이 난다
			})))
			.isInstanceOf(OptimisticLockingFailureException.class);

		// then
		assertThat(optimisticLockFailures()).isEqualTo(before + 1);
	}

	@Test
	void 네이티브_쿼리와_벌크_쿼리도_JPQL_조회와_똑같이_센다() {
		// given
		int count = transactionTemplate.execute(status -> {
			try (QueryCountContext.Scope sut = QueryCountContext.open()) {
				// when
				entityManager.createNativeQuery("select 1").getSingleResult();
				entityManager.createNativeQuery("update product set version = version where id = :id")
					.setParameter("id", productId)
					.executeUpdate();
				entityManager.createQuery("select count(p) from Product p", Long.class).getSingleResult();
				return sut.count();
			}
		});

		// then
		assertThat(count).isEqualTo(3);
	}

	// 충돌한 엔티티(Product) 태그의 값. 아직 한 번도 충돌하지 않아 지표가 없으면 0이다
	private double optimisticLockFailures() {
		Counter counter = meterRegistry.find("jpa.optimistic.lock.failures").tag("entity", "Product").counter();
		return counter == null ? 0 : counter.count();
	}

	private Long optionId() {
		return transactionTemplate.execute(status ->
			productRepository.findById(productId).orElseThrow().getOptions().getFirst().getId());
	}
}
