package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductOptionSpec;

public record ProductOptionCommand(
	Long id, // 기존 옵션 id. 새 옵션이면 null
	String name,
	Long listPrice,
	Long salePrice,
	int totalStock
) {

	// 상품 옵션 명세로 바꾼다
	public ProductOptionSpec toSpec() {
		return new ProductOptionSpec(id, name, listPrice, salePrice, totalStock);
	}
}
