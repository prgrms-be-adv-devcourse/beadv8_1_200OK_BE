package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;

import java.util.List;

public record ProductUpdateCommand(
	Integer version, // 수정 화면을 열 때 받은 상품 version. DB 값과 다르면 동시 수정
	String name,
	String descriptionHtml,
	ProductCategory category,
	ProductRegion region,
	ProductStatus status,
	ProductVisibility visibility,
	List<String> imageUrls,
	List<ProductOptionSpec> options
) {
}
