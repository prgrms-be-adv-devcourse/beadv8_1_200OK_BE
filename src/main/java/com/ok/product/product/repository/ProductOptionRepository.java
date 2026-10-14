package com.ok.product.product.repository;

import com.ok.product.product.domain.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 재고 두 컬럼(total_stock, allocated_quantity)은 엔티티에서 updatable = false라 아래 쿼리로만 바뀐다.
 * 모두 반영된 행 수를 돌려준다. 0이면 조건 불충족(재고 부족 등)이다.
 * 쿼리는 영속성 컨텍스트를 거치지 않으므로, 같은 트랜잭션에서 바뀐 값이 필요하면 다시 조회한다.
 */
public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

	// 배정: 판매 가능 수량(총재고 - 배정 수량)이 충분할 때만 배정 수량을 늘린다
	@Modifying
	@Query("""
		update ProductOption o
		set o.allocatedQuantity = o.allocatedQuantity + :quantity, o.updatedAt = local datetime
		where o.id = :id and o.deletedAt is null and o.totalStock - o.allocatedQuantity >= :quantity
		""")
	int allocate(@Param("id") Long id, @Param("quantity") int quantity);

	// 해제: 삭제된 옵션도 해제한다
	@Modifying
	@Query("""
		update ProductOption o
		set o.allocatedQuantity = o.allocatedQuantity - :quantity, o.updatedAt = local datetime
		where o.id = :id and o.allocatedQuantity >= :quantity
		""")
	int release(@Param("id") Long id, @Param("quantity") int quantity);

	// 재고 변경: 현재 배정 수량(allocatedQuantity)보다 작게는 못 바꾼다.
	@Modifying
	@Query("""
		update ProductOption o
		set o.totalStock = :totalStock, o.updatedAt = local datetime
		where o.id = :id and :totalStock >= o.allocatedQuantity
		""")
	int updateTotalStock(@Param("id") Long id, @Param("totalStock") int totalStock);
}
