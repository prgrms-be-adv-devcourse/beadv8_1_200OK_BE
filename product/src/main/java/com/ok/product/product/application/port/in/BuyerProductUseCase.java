package com.ok.product.product.application.port.in;

import java.util.List;

import com.ok.product.product.application.dto.ProductCategoryGroupResult;
import com.ok.product.product.application.dto.ProductDetailResult;
import com.ok.product.product.application.dto.ProductSearchQuery;
import com.ok.product.product.application.dto.ProductSearchResult;

/**
 * 구매자가 공개된 상품과 카테고리를 조회하는 유스케이스.
 */
public interface BuyerProductUseCase {

	/**
	 * 구매자에게 보이는 상품 한 페이지를 검색어·필터·정렬대로 찾고, 검색어가 품목 이름의 오타로 보이면 바른 품목을 함께 제안한다.
	 * 검색어가 있는 첫 페이지 검색이면 검색어 로그를 남긴다. 로그 저장은 기다리지 않고, 실패해도 검색은 성공한다.
	 *
	 * @throws com.ok.common.exception.RestApiException 목록 SQL이 시간 제한을 넘으면 {@code PRODUCT_SEARCH_TIMEOUT}. 이때는 로그를 남기지 않는다
	 */
	ProductSearchResult searchVisibleProducts(ProductSearchQuery query);

	ProductDetailResult getVisibleProduct(Long productId);

	List<ProductCategoryGroupResult> getCategories();

}
