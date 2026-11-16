package com.ok.product.product.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.ok.product.product.domain.ProductStockAllocation;
import com.ok.product.product.domain.StockAllocationStatus;

/**
 * 재고 배정 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * 해제는 잠그지 않고 id만 읽은 뒤({@link #findAllocatedIdsByOrderId}) PK로 잠가 다시 읽는다({@link #findAllocatedByIdInForUpdate}).
 * 두 단계로 나눈 이유는 구현의 쿼리 주석에 있다.
 */
public interface ProductStockAllocationRepository {

	// 이미 배정된 주문인지 (allocate 멱등 처리)
	boolean existsByOrderId(Long orderId);

	// 해제한 주문인지
	boolean existsByOrderIdAndStatus(Long orderId, StockAllocationStatus status);

	List<Long> findAllocatedIdsByOrderId(Long orderId);

	/**
	 * ALLOCATED인 배정 기록을 PK로 잠가 옵션 id 오름차순으로 읽는다.
	 */
	List<ProductStockAllocation> findAllocatedByIdInForUpdate(Collection<Long> ids);

	/**
	 * ALLOCATED일 때만 RELEASED로 바꾼다.
	 *
	 * @return 1이면 이번 호출이 해제한 것, 0이면 이미 해제됨
	 */
	int release(Long id);

	Optional<Long> findIdByOrderIdAndProductOptionId(Long orderId, Long productOptionId);

	/**
	 * ALLOCATED이고 남은 수량이 충분할 때만 수량을 줄인다. 0이 돼도 ALLOCATED로 둔다.
	 *
	 * @return 1이면 줄임, 0이면 해제 가능 수량 초과(이미 해제한 기록 포함)
	 */
	int decreaseQuantity(Long id, int quantity);

	<S extends ProductStockAllocation> S save(S allocation);

}
