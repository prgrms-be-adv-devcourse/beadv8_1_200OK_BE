package com.ok.product.product.repository;

import com.ok.product.ProductSchema;
import com.ok.product.product.domain.ProductStockAllocationHeader;
import com.ok.product.product.domain.StockAllocationStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

// 아래 upsert는 같은 주문의 배정·해제를 줄 세우는 헤더 행 X 잠금을 잡는다.
// 중복일 때만 넘어가고 다른 오류(null 등)는 그대로 실패한다. INSERT IGNORE는 다른 오류도 경고로 바꿔 삼키고,
// 중복 키에 S 잠금을 잡은 뒤 FOR UPDATE에서 X로 올리다 같은 주문의 배정끼리 데드락이 나서 쓰지 않는다.
// Connector/J 기본 설정은 변경 없는 갱신도 영향 행 1을 돌려주므로, 반환값으로 새로 넣었는지 판정하지 않는다.
// 컬럼을 직접 나열하므로 ProductStockAllocationHeader·BaseTime에 NOT NULL 컬럼이 추가되면 이 쿼리들도 함께 수정해야 한다.
// 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다.
// JpaRepository를 상속하지 않는다. findById·save는 일반 SELECT를 먼저 실행해, upsert보다 앞서 부르면 스냅샷이 일찍 잡혀
// 해제가 진행 중인 배정의 커밋을 보지 못한다(ProductStockService 클래스 설명 참고)
public interface ProductStockAllocationHeaderRepository extends Repository<ProductStockAllocationHeader, Long> {

	// 없으면 ALLOCATED로 만들고, 있으면 상태를 바꾸지 않고 잠금만 잡는다
	@Modifying
	@Query(value = "INSERT INTO " + ProductSchema.NAME + """
		.product_stock_allocation_header (order_id, status, created_at, updated_at)
		VALUES (:orderId, 'ALLOCATED', :now, :now)
		ON DUPLICATE KEY UPDATE order_id = order_id
		""", nativeQuery = true)
	void insertAllocatedIfAbsent(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

	// 없어도 RELEASED로 만든다. 해제가 배정보다 먼저 와도 이 기록이 뒤늦은 배정을 막는다
	@Modifying
	@Query(value = "INSERT INTO " + ProductSchema.NAME + """
		.product_stock_allocation_header (order_id, status, created_at, updated_at)
		VALUES (:orderId, 'RELEASED', :now, :now)
		ON DUPLICATE KEY UPDATE status = 'RELEASED', updated_at = :now
		""", nativeQuery = true)
	void upsertReleased(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

	// upsert로 잡은 잠금 아래에서 읽는다. 같은 트랜잭션이 이미 X 잠금을 쥐고 있어 기다리지 않는다
	@Query(value = "SELECT status FROM " + ProductSchema.NAME + """
		.product_stock_allocation_header
		WHERE order_id = :orderId
		FOR UPDATE
		""", nativeQuery = true)
	StockAllocationStatus findStatusForUpdate(@Param("orderId") Long orderId);
}
