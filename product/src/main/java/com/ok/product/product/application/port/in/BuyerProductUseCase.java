package com.ok.product.product.application.port.in;

import java.util.List;

import com.ok.product.product.application.dto.ProductCategoryGroupResult;
import com.ok.product.product.application.dto.ProductDetailResult;
import com.ok.product.product.application.dto.ProductSearchQuery;
import com.ok.product.product.application.dto.ProductSearchResult;
import com.ok.product.product.application.dto.ProductSummaryResult;

/**
 * 구매자가 공개된 상품과 카테고리를 조회하는 유스케이스.
 */
public interface BuyerProductUseCase {

	// 한 번에 조회할 수 있는 상품 카드 수. 다른 목록 API의 페이지 크기 상한과 같게 둔다
	int MAX_SUMMARY_IDS = 100;

	/**
	 * 구매자에게 보이는 상품 한 페이지를 검색어·필터·정렬대로 찾고, 검색어가 품목 이름의 오타로 보이면 바른 품목을 함께 제안한다.
	 * 검색어가 있는 첫 페이지 검색이면 검색어 로그를 남긴다. 로그 저장은 기다리지 않고, 실패해도 검색은 성공한다.
	 *
	 * @throws com.ok.common.exception.RestApiException 목록 SQL이 시간 제한을 넘으면 {@code PRODUCT_SEARCH_TIMEOUT}. 이때는 로그를 남기지 않는다
	 */
	ProductSearchResult searchVisibleProducts(ProductSearchQuery query);

	ProductDetailResult getVisibleProduct(Long productId);

	/**
	 * 구매자에게 보이는 상품의 카드를 ID 여러 개로 한 번에 조회한다.
	 * 숨겼거나 삭제됐거나 없는 상품은 오류 없이 뺀다. 결과는 요청한 ID 순서이고, 같은 ID는 한 번만 담는다.
	 */
	List<ProductSummaryResult> getVisibleProductSummaries(List<Long> productIds);

	List<ProductCategoryGroupResult> getCategories();

}
