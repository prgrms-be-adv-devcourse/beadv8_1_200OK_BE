package com.ok.product.product.repository;

import com.ok.product.product.domain.ProductStockPartialRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductStockPartialReleaseRepository extends JpaRepository<ProductStockPartialRelease, Long> {

	// 이미 처리한 환불인지 (partialRelease 멱등 처리)
	Optional<ProductStockPartialRelease> findByRefundId(Long refundId);
}
