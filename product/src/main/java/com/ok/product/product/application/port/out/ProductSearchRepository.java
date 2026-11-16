package com.ok.product.product.application.port.out;

import java.util.List;
import java.util.Optional;

import com.ok.product.product.domain.ProductSearch;

/**
 * 상품 검색 행 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ProductSearchRepository {

	Optional<ProductSearch> findById(Long productId);

	<S extends ProductSearch> S save(S productSearch);

	/**
	 * 검색 행이 없거나 정규화 버전이 {@code currentVersion}보다 낮은 미삭제 상품의 ID를 {@code afterId} 다음부터 오름차순으로 읽는다.
	 */
	List<Long> findBackfillTargetIds(long afterId, int currentVersion, int limit);

}
