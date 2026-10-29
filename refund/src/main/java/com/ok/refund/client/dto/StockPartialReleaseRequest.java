package com.ok.refund.client.dto;

/**
 * 상품 모듈 {@code POST /internal/v1/products/stock-allocations/{orderId}/partial-release} 요청(요청서 8.2 기준 가정).
 */
public record StockPartialReleaseRequest(Long refundId, Long productOptionId, int quantity) {
}
