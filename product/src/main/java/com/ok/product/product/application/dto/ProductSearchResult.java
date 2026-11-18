package com.ok.product.product.application.dto;

import org.springframework.data.domain.Page;

/**
 * 구매자 상품 목록 검색 결과.
 *
 * @param suggestion 검색어가 품목 이름의 오타로 보일 때 제안할 품목. 검색어가 없거나 제안할 품목이 없으면 null
 */
public record ProductSearchResult(Page<ProductSummaryResult> products, String suggestion) {
}
