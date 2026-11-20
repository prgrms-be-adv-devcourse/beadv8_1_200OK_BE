package com.ok.product.product.application.port.out;

import java.util.Optional;

import com.ok.product.product.domain.ProductStockPartialRelease;

/**
 * 재고 일부 해제 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ProductStockPartialReleaseRepository {

	// 이미 처리한 환불인지 (partialRelease 멱등 처리)
	Optional<ProductStockPartialRelease> findByRefundId(Long refundId);

	<S extends ProductStockPartialRelease> S save(S partialRelease);

}
