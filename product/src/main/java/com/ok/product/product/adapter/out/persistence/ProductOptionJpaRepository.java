package com.ok.product.product.adapter.out.persistence;

import com.ok.product.product.application.port.out.ProductOptionRepository;
import com.ok.product.product.domain.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 재고 두 컬럼(total_stock, allocated_quantity)은 엔티티에서 updatable = false라 아래 쿼리로만 바뀐다.
 * 쿼리는 영속성 컨텍스트를 거치지 않으므로, 같은 트랜잭션에서 바뀐 값이 필요하면 다시 조회한다.
 */
public interface ProductOptionJpaRepository extends JpaRepository<ProductOption, Long>, ProductOptionRepository {

	// JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
	// 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
	@Override
	Optional<ProductOption> findById(Long id);

	// 옵션 조회
	@Override
	@Query("""
		select o from ProductOption o join fetch o.product
		where o.id in :ids
		order by o.id
		""")
	List<ProductOption> findAllWithProductByIdIn(@Param("ids") Collection<Long> ids);

	// 리뷰 작성 시 옵션이 그 상품의 옵션인지 확인한다. 구매 후 옵션이 지워질 수 있어 삭제된 옵션도 포함한다
	boolean existsByIdAndProductId(Long id, Long productId);

	// 배정: 판매 가능 수량(총재고 - 배정 수량)이 충분할 때만 배정 수량을 늘린다
	@Override
	@Modifying
	@Query("""
		update ProductOption o
		set o.allocatedQuantity = o.allocatedQuantity + :quantity, o.updatedAt = local datetime
		where o.id = :id and o.deletedAt is null and o.totalStock - o.allocatedQuantity >= :quantity
		""")
	int allocate(@Param("id") Long id, @Param("quantity") int quantity);

	// 해제: 삭제된 옵션도 해제한다
	@Override
	@Modifying
	@Query("""
		update ProductOption o
		set o.allocatedQuantity = o.allocatedQuantity - :quantity, o.updatedAt = local datetime
		where o.id = :id and o.allocatedQuantity >= :quantity
		""")
	int release(@Param("id") Long id, @Param("quantity") int quantity);

	// 재고 변경: 현재 배정 수량(allocatedQuantity)보다 작게는 못 바꾼다.
	@Override
	@Modifying
	@Query("""
		update ProductOption o
		set o.totalStock = :totalStock, o.updatedAt = local datetime
		where o.id = :id and :totalStock >= o.allocatedQuantity
		""")
	int updateTotalStock(@Param("id") Long id, @Param("totalStock") int totalStock);
}
