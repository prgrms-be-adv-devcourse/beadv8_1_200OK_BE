package com.ok.product.product.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.ok.product.product.domain.ProductOption;

/**
 * 상품 옵션 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * 재고 두 컬럼(total_stock, allocated_quantity)은 아래 갱신 메서드로만 바뀐다. 갱신은 영속성 컨텍스트를 거치지 않으므로,
 * 같은 트랜잭션에서 바뀐 값이 필요하면 다시 조회한다.
 */
public interface ProductOptionRepository {

	List<ProductOption> findAllWithProductByIdIn(Collection<Long> ids);

	/**
	 * 판매 가능 수량(총재고 - 배정 수량)이 충분할 때만 배정 수량을 늘린다.
	 *
	 * @return 1이면 배정, 0이면 수량 부족이거나 삭제된 옵션
	 */
	int allocate(Long id, int quantity);

	/**
	 * 배정 수량을 줄인다. 삭제된 옵션도 해제한다.
	 *
	 * @return 1이면 해제, 0이면 배정 수량보다 많이 해제하려 함
	 */
	int release(Long id, int quantity);

	/**
	 * 총재고를 바꾼다. 현재 배정 수량보다 작게는 못 바꾼다.
	 *
	 * @return 1이면 변경, 0이면 배정 수량보다 작음
	 */
	int updateTotalStock(Long id, int totalStock);

	Optional<ProductOption> findById(Long id);

	List<ProductOption> findAllById(Iterable<Long> ids);

}
