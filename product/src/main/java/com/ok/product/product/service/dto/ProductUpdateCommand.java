package com.ok.product.product.service.dto;

import java.util.List;

public record ProductUpdateCommand(
	Integer version, // 수정 화면을 열 때 받은 상품 version. DB 값과 다르면 동시 수정
	String name,
	String descriptionHtml,
	ProductCategoryCode category,
	ProductRegionCode region,
	ProductStatusCode status,
	ProductVisibilityCode visibility,
	List<String> imageUrls,
	List<ProductOptionCommand> options
) {
}
