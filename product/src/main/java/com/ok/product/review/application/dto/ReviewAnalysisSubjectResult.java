package com.ok.product.review.application.dto;

import com.ok.product.product.application.dto.ProductCategoryCode;

/**
 * 분석기 입력에 넣을 상품·옵션 정보다. 운영 작업자가 리뷰의 상품·옵션에서 읽는 값과 같다.
 */
public record ReviewAnalysisSubjectResult(
	String productName,
	String optionName,
	ProductCategoryCode category
) {
}
