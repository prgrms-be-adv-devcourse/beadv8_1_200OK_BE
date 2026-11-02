package com.ok.refund.client.dto;

/**
 * 주문 모듈 {@code POST /internal/v1/orders/items/{orderItemId}/refunds} 요청(요청서 9.2 기준 가정).
 *
 * @param deductedShippingFee 이 취소로 환불에서 뺀 기본 배송비 (원). 없으면 0. 주문은 구매확정 때 판매자 몫 배송비에 더한다(#327 합의)
 */
public record OrderRefundApplyRequest(Long refundId, int quantity, long deductedShippingFee) {
}
