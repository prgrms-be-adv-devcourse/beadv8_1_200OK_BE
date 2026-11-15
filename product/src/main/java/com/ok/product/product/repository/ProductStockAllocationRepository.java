package com.ok.product.product.repository;

import com.ok.product.product.domain.ProductStockAllocation;
import com.ok.product.product.domain.StockAllocationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductStockAllocationRepository extends JpaRepository<ProductStockAllocation, Long> {

	// 이미 배정된 주문인지 (allocate 멱등 처리)
	boolean existsByOrderId(Long orderId);

	// 해제한 주문인지
	boolean existsByOrderIdAndStatus(Long orderId, StockAllocationStatus status);

	// 해제 대상 1단계. 잠그지 않고 id만 읽는다. 주문 id로 바로 잠그면 (order_id, product_option_id) 유니크 인덱스의
	// 범위 잠금이라 갭 락까지 잡혀, 더 작은 주문의 배정(INSERT)이 그 갭을 기다리는 동안 이쪽은 그 배정이 쥔 옵션 행을 기다려 데드락이 난다.
	// 엔티티가 아니라 id만 읽는다. 엔티티를 먼저 읽으면 2단계 잠금 조회가 1차 캐시의 낡은 엔티티를 돌려줘 락을 잡은 뒤의 수량을 못 본다
	@Query("""
		select a.id from ProductStockAllocation a
		where a.orderId = :orderId and a.status = com.ok.product.product.domain.StockAllocationStatus.ALLOCATED
		""")
	List<Long> findAllocatedIdsByOrderId(@Param("orderId") Long orderId);

	// 해제 대상 2단계. PK로 잠가 레코드 락만 잡는다(갭 락 없음). 락을 잡은 뒤의 최신 상태로 ALLOCATED를 다시 거르고,
	// 일부 해제가 그사이 줄인 수량도 이 값으로 읽는다. 옵션 id 오름차순으로 처리해 옵션 행 잠금 순서를 배정과 맞춰 데드락을 막는다
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select a from ProductStockAllocation a
		where a.id in :ids and a.status = com.ok.product.product.domain.StockAllocationStatus.ALLOCATED
		order by a.productOptionId
		""")
	List<ProductStockAllocation> findAllocatedByIdInForUpdate(@Param("ids") Collection<Long> ids);

	// ALLOCATED일 때만 RELEASED로 바꾼다. 1이면 이번 호출이 해제한 것, 0이면 이미 해제됨
	@Modifying
	@Query("""
		update ProductStockAllocation a
		set a.status = com.ok.product.product.domain.StockAllocationStatus.RELEASED, a.updatedAt = local datetime
		where a.id = :id and a.status = com.ok.product.product.domain.StockAllocationStatus.ALLOCATED
		""")
	int release(@Param("id") Long id);

	// 일부 해제 대상. (order_id, product_option_id) 유니크라 최대 1건
	// 엔티티가 아니라 id만 읽는다. 수량은 뒤의 조건부 UPDATE가 바꾸므로, 엔티티를 읽으면 1차 캐시에 낡은 수량이 남는다
	@Query("""
		select a.id from ProductStockAllocation a
		where a.orderId = :orderId and a.productOptionId = :productOptionId
		""")
	Optional<Long> findIdByOrderIdAndProductOptionId(
		@Param("orderId") Long orderId, @Param("productOptionId") Long productOptionId);

	// ALLOCATED이고 남은 수량이 충분할 때만 줄인다. 0이면 해제 가능 수량 초과(이미 해제한 기록 포함)
	// 0이 돼도 ALLOCATED로 둔다. 일부 해제는 주문 해제가 아니다. allocate()는 헤더가 없던 과거 주문을 막으려고
	// 배정 기록의 RELEASED도 해제한 주문으로 보므로, RELEASED로 바꾸면 같은 주문의 배정 재요청이 204 대신 409를 받는다
	@Modifying
	@Query("""
		update ProductStockAllocation a
		set a.quantity = a.quantity - :quantity, a.updatedAt = local datetime
		where a.id = :id and a.status = com.ok.product.product.domain.StockAllocationStatus.ALLOCATED
			and a.quantity >= :quantity
		""")
	int decreaseQuantity(@Param("id") Long id, @Param("quantity") int quantity);
}
