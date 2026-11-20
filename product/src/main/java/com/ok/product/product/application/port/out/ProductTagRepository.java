package com.ok.product.product.application.port.out;

import java.util.List;

import com.ok.product.product.domain.ProductTag;

/**
 * 상품 태그 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ProductTagRepository {

	List<ProductTag> findByProductId(Long productId);

	<S extends ProductTag> List<S> saveAll(Iterable<S> productTags);

	void deleteAll(Iterable<? extends ProductTag> productTags);

}
