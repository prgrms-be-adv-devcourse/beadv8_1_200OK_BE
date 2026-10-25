package com.ok.product.product.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;

import com.ok.common.config.JpaConfig;
import com.ok.product.product.domain.ProductStockAllocation;
import com.ok.product.product.domain.StockAllocationStatus;
import com.ok.testsupport.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

@ActiveProfiles("test")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class ProductStockAllocationRepositoryTest {

	@Autowired
	ProductStockAllocationRepository repository;

	@Autowired
	EntityManager entityManager;

	@Test
	void 같은_주문이_같은_옵션을_두_번_배정할_수_없다() {
		repository.saveAndFlush(ProductStockAllocation.allocate(100L, 11L, 2));

		assertThatThrownBy(() -> repository.saveAndFlush(ProductStockAllocation.allocate(100L, 11L, 3)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 배정된_주문인지_확인한다() {
		repository.saveAndFlush(ProductStockAllocation.allocate(100L, 11L, 2));

		assertThat(repository.existsByOrderId(100L)).isTrue();
		assertThat(repository.existsByOrderId(200L)).isFalse();
	}

	@Test
	void 해제_대상은_옵션_id_오름차순으로_조회된다() {
		repository.save(ProductStockAllocation.allocate(100L, 12L, 1));
		repository.save(ProductStockAllocation.allocate(100L, 11L, 1));
		repository.saveAndFlush(ProductStockAllocation.allocate(200L, 10L, 1));

		assertThat(repository.findByOrderIdAndStatusOrderByProductOptionIdAsc(100L, StockAllocationStatus.ALLOCATED))
			.extracting(ProductStockAllocation::getProductOptionId)
			.containsExactly(11L, 12L);
	}

	@Test
	void 해제는_한_번만_반영된다() {
		Long id = repository.saveAndFlush(ProductStockAllocation.allocate(100L, 11L, 2)).getId();

		assertThat(repository.release(id)).isEqualTo(1);
		assertThat(repository.release(id)).isZero();

		entityManager.clear();
		assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(StockAllocationStatus.RELEASED);
		assertThat(repository.findByOrderIdAndStatusOrderByProductOptionIdAsc(100L, StockAllocationStatus.ALLOCATED))
			.isEmpty();
	}
}
