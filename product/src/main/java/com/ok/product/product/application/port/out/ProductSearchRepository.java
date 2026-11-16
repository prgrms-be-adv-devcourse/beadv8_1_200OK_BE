package com.ok.product.product.application.port.out;

import java.util.Optional;

import com.ok.product.product.domain.ProductSearch;

/**
 * 상품 검색 행 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ProductSearchRepository {

	Optional<ProductSearch> findById(Long productId);

	<S extends ProductSearch> S save(S productSearch);

}
