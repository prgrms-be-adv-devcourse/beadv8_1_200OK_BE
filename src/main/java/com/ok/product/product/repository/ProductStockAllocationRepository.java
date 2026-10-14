package com.ok.product.product.repository;

import com.ok.product.product.domain.ProductStockAllocation;
import com.ok.product.product.domain.StockAllocationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductStockAllocationRepository extends JpaRepository<ProductStockAllocation, Long> {

	// 이미 배정된 주문인지 (allocate 멱등 처리)
	boolean existsByOrderId(Long orderId);

	// 해제 대상. 옵션 id 오름차순으로 잠가 데드락을 막는다
	List<ProductStockAllocation> findByOrderIdAndStatusOrderByProductOptionIdAsc(Long orderId,
		StockAllocationStatus status);

	// ALLOCATED일 때만 RELEASED로 바꾼다. 1이면 이번 호출이 해제한 것, 0이면 이미 해제됨
	@Modifying
	@Query("""
		update ProductStockAllocation a
		set a.status = com.ok.product.product.domain.StockAllocationStatus.RELEASED, a.updatedAt = local datetime
		where a.id = :id and a.status = com.ok.product.product.domain.StockAllocationStatus.ALLOCATED
		""")
	int release(@Param("id") Long id);
}
