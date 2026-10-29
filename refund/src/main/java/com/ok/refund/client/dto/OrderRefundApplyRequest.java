package com.ok.refund.client.dto;

/**
 * 주문 모듈 {@code POST /internal/v1/orders/items/{orderItemId}/refunds} 요청(요청서 9.2 기준 가정).
 */
public record OrderRefundApplyRequest(Long refundId, int quantity) {
}
