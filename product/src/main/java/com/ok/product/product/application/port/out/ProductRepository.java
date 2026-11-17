package com.ok.product.product.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductVisibility;

/**
 * 상품 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * Spring Data에서 물려받던 메서드도 서비스가 쓰는 것만 같은 시그니처로 둔다. 구현체가 Spring Data 프록시라 동작은 그대로다.
 */
public interface ProductRepository {

	// 활성 상태 상품 조회
	Optional<Product> findByIdAndDeletedAtIsNull(Long id);

	// 구매자 상세 조회
	Optional<Product> findByIdAndVisibilityAndDeletedAtIsNull(Long id, ProductVisibility visibility);

	/**
	 * 수정용으로 조회한다. 옵션만 바뀐 수정에서도 커밋 시 version을 무조건 +1 해서 동시 수정을 감지한다.
	 */
	Optional<Product> findForUpdateByIdAndDeletedAtIsNull(Long id);

	/**
	 * 리뷰 집계를 DB에서 더한다.
	 * 영속성 컨텍스트를 거치지 않으므로 같은 트랜잭션에서 읽어 둔 Product의 집계 값은 갱신 전 값이다.
	 */
	int addReviewStats(Long productId, int countDelta, int ratingDelta);

	List<Product> findAllById(Iterable<Long> ids);

	/**
	 * 검색 행 백필용. 옵션까지 한 번의 쿼리로 읽어 상품마다 옵션을 지연 로딩하지 않게 한다.
	 */
	List<Product> findAllWithOptionsByIdIn(Collection<Long> ids);

	<S extends Product> S save(S product);

}
