package com.ok.product.product.service.dto;

// 환불 한 건의 옵션 재고 일부 해제
public record StockPartialReleaseCommand(
	Long orderId,
	Long refundId,
	Long productOptionId,
	int quantity
) {
}
