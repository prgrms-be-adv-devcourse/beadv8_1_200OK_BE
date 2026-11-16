package com.ok.product.product.application.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductSearchKeyword;

/**
 * 검색어로 구매자 상품 목록을 찾는 출력 포트. 구현은 {@code adapter.out.persistence}의 JdbcClient 구현이다.
 * 점수를 계산하는 SQL이라 엔티티 대신 상품 ID만 돌려준다.
 */
public interface ProductKeywordSearchRepository {

	/**
	 * 구매자에게 보이는 상품 중 검색어 점수가 하한 이상인 상품의 ID 한 페이지를 정확도순으로 읽는다. null인 조건은 거르지 않는다.
	 * 전체 개수도 하한 이상인 상품만 센다.
	 *
	 * @param minScore 이보다 점수가 낮은 상품은 결과와 개수에서 뺀다
	 */
	Page<Long> findVisibleProductIds(ProductSearchKeyword keyword, ProductCategoryGroup categoryGroup,
		ProductCategory category, ProductRegion region, double minScore, Pageable pageable);

}
