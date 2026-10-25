package com.ok.product.product.service.dto;

// 주문 한 줄의 옵션별 재고 변경 수량
public record StockChange(
	Long optionId,
	int quantity
) {
}
