package com.ok.order.order.application.dto;

// 환불 모듈이 승인 직후 알려 준 주문 상품 취소 반영 요청
public record RecordOrderItemRefundCommand(
        Long orderItemId,
        Long refundId,
        int quantity,
        long deductedShippingFee   // 환불 금액에서 뺀 배송비 (원). 빼지 않았으면 0
) {
}
