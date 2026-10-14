package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductOption;

// 구매자에게는 재고 수량 대신 품절 여부만 준다
public record ProductOptionResult(
	Long optionId,
	String name,
	Long listPrice,
	Long salePrice,
	int discountRate,
	boolean soldOut
) {

	public static ProductOptionResult from(ProductOption option) {
		return new ProductOptionResult(option.getId(), option.getName(), option.getListPrice(), option.getSalePrice(),
			option.discountRate(), option.availableStock() <= 0);
	}
}
