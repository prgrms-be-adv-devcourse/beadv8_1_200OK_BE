package com.ok.product.product.application.dto;

import com.ok.product.product.domain.ProductOption;

// 구매자에게는 재고 수량 대신 품절·품절 임박 여부만 준다
public record ProductOptionResult(
	Long optionId,
	String name,
	Long listPrice,
	Long salePrice,
	int discountRate,
	boolean soldOut,
	boolean lowStock
) {

	public static ProductOptionResult from(ProductOption option, int lowStockThreshold) {
		return new ProductOptionResult(option.getId(), option.getName(), option.getListPrice(), option.getSalePrice(),
			option.discountRate(), option.availableStock() <= 0, option.isLowStock(lowStockThreshold));
	}
}
