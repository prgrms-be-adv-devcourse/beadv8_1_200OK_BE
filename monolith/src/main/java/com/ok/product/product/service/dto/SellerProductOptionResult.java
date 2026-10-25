package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductOption;

// 판매자 수정 화면용 옵션. 총재고를 배정 수량보다 낮출 수 없으므로 배정 수량도 함께 준다
public record SellerProductOptionResult(
	Long optionId,
	String name,
	Long listPrice,
	Long salePrice,
	int totalStock,
	int allocatedQuantity,
	int availableStock
) {

	public static SellerProductOptionResult from(ProductOption option) {
		return new SellerProductOptionResult(option.getId(), option.getName(), option.getListPrice(),
			option.getSalePrice(), option.getTotalStock(), option.getAllocatedQuantity(), option.availableStock());
	}
}
