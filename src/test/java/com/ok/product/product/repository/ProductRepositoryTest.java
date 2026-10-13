package com.ok.product.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ok.config.JpaConfig;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.testsupport.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

@ActiveProfiles("test")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class ProductRepositoryTest {

	@Autowired
	ProductRepository productRepository;

	@Autowired
	EntityManager entityManager;

	@Autowired
	PlatformTransactionManager transactionManager;

	TransactionTemplate transactionTemplate;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@Test
	void 상품을_저장하면_옵션과_이미지도_함께_저장된다() {
		Long id = save().getId();
		entityManager.clear();

		Product found = productRepository.findById(id).orElseThrow();
		assertThat(found.getOptions()).hasSize(2);
		assertThat(found.getImages()).hasSize(1);
		assertThat(found.getVersion()).isZero();
	}

	@Test
	void 삭제된_상품은_조회되지_않는다() {
		Product product = save();
		product.delete();
		entityManager.flush();

		assertThat(productRepository.findByIdAndDeletedAtIsNull(product.getId())).isEmpty();
		assertThat(productRepository.findForUpdateByIdAndDeletedAtIsNull(product.getId())).isEmpty();
	}

	// OPTIMISTIC_FORCE_INCREMENT는 커밋 직전에 version을 올리므로, 이 테스트만 실제로 커밋한다
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void 수정용으로_조회하면_아무것도_안_바꿔도_커밋할_때_version이_오른다() {
		Long id = transactionTemplate.execute(status -> save().getId());

		transactionTemplate.executeWithoutResult(status -> productRepository.findForUpdateByIdAndDeletedAtIsNull(id).orElseThrow());

		assertThat(productRepository.findById(id).orElseThrow().getVersion()).isEqualTo(1);
		productRepository.deleteById(id);
	}

	private Product save() {
		return productRepository.saveAndFlush(Product.create(1L, "꿀고구마", "<p>설명</p>",
			ProductCategory.ROOT_CROP, ProductRegion.JEONNAM,
			List.of(ProductOptionSpec.newOption("3kg", null, 30_000L, 10),
				ProductOptionSpec.newOption("5kg", null, 45_000L, 10)),
			List.of("https://bucket/product/a.jpg")));
	}
}
