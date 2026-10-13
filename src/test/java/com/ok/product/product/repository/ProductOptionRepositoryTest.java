package com.ok.product.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.ok.config.JpaConfig;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.testsupport.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

@ActiveProfiles("test")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class ProductOptionRepositoryTest {

	@Autowired
	ProductRepository productRepository;

	@Autowired
	ProductOptionRepository productOptionRepository;

	@Autowired
	EntityManager entityManager;

	Long optionId;

	@BeforeEach
	void setUp() {
		Product product = productRepository.saveAndFlush(Product.create(1L, "꿀고구마", "<p>설명</p>",
			ProductCategory.ROOT_CROP, ProductRegion.JEONNAM,
			List.of(ProductOptionSpec.newOption("3kg", null, 30_000L, 10)),
			List.of("https://bucket/product/a.jpg")));
		optionId = product.getOptions().get(0).getId();
	}

	@Test
	void 판매_가능_수량_안에서만_배정된다() {
		assertThat(productOptionRepository.allocate(optionId, 7)).isEqualTo(1);
		assertThat(productOptionRepository.allocate(optionId, 4)).isZero(); // 남은 3개보다 많음

		ProductOption option = reload();
		assertThat(option.getAllocatedQuantity()).isEqualTo(7);
		assertThat(option.availableStock()).isEqualTo(3);
	}

	@Test
	void 삭제된_옵션에는_배정할_수_없다() {
		productOptionRepository.findById(optionId).orElseThrow().getProduct().delete(LocalDateTime.now());
		entityManager.flush();

		assertThat(productOptionRepository.allocate(optionId, 1)).isZero();
	}

	@Test
	void 해제하면_판매_가능_수량이_돌아온다() {
		productOptionRepository.allocate(optionId, 5);

		assertThat(productOptionRepository.release(optionId, 5)).isEqualTo(1);
		assertThat(reload().availableStock()).isEqualTo(10);
	}

	@Test
	void 배정_수량보다_많이_해제할_수_없다() {
		productOptionRepository.allocate(optionId, 2);

		assertThat(productOptionRepository.release(optionId, 3)).isZero();
		assertThat(reload().getAllocatedQuantity()).isEqualTo(2);
	}

	@Test
	void 총재고는_배정_수량보다_작게_바꿀_수_없다() {
		productOptionRepository.allocate(optionId, 6);

		assertThat(productOptionRepository.updateTotalStock(optionId, 5)).isZero();
		assertThat(productOptionRepository.updateTotalStock(optionId, 6)).isEqualTo(1);
		assertThat(reload().getTotalStock()).isEqualTo(6);
	}

	@Test
	void 엔티티를_수정해_저장해도_재고_컬럼은_덮어쓰지_않는다() {
		ProductOption stale = productOptionRepository.findById(optionId).orElseThrow(); // 배정 0인 상태로 읽음
		productOptionRepository.allocate(optionId, 4);                                  // DB는 배정 4

		ReflectionTestUtils.setField(stale, "name", "3kg 특품");                         // 이름만 바꿔 UPDATE가 나가게 함
		entityManager.flush();

		assertThat(reload().getAllocatedQuantity()).isEqualTo(4);
	}

	private ProductOption reload() {
		entityManager.flush();
		entityManager.clear();
		return productOptionRepository.findById(optionId).orElseThrow();
	}

}
