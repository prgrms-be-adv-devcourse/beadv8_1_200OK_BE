package com.ok.product.product.application.port.in;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;

import com.ok.product.product.application.dto.ProductCategoryGroupResult;
import com.ok.product.product.application.dto.ProductDetailResult;
import com.ok.product.product.application.dto.ProductSearchQuery;
import com.ok.product.product.application.dto.ProductSummaryResult;

/**
 * 구매자가 공개된 상품과 카테고리를 조회하는 유스케이스.
 */
public interface BuyerProductUseCase {

	/**
	 * 구매자에게 보이는 상품 한 페이지를 검색어·필터·정렬대로 찾는다.
	 *
	 * @throws com.ok.common.exception.RestApiException 목록 SQL이 시간 제한을 넘으면 {@code PRODUCT_SEARCH_TIMEOUT}
	 */
	Page<ProductSummaryResult> searchVisibleProducts(ProductSearchQuery query);

	/**
	 * 검색어가 품목 이름의 오타로 보이면 바른 품목을 제안한다. 검색 결과는 바꾸지 않는다.
	 *
	 * @return 제안할 품목. 검색어가 없거나 제안할 품목이 없으면 빈 값
	 */
	Optional<String> suggestKeyword(String keyword);

	ProductDetailResult getVisibleProduct(Long productId);

	List<ProductCategoryGroupResult> getCategories();

}
