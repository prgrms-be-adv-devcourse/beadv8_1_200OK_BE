package com.ok.order.order.domain;

public enum OrderItemStatus {
    PAYMENT_PENDING,      // 결제 대기
    PAYMENT_FAILED,       // 결제 실패
    PREPARING,            // 배송 준비 (결제 완료)
    SHIPPING,             // 배송중
    DELIVERED,            // 배송 완료
    PURCHASE_CONFIRMED,   // 구매확정
    CANCEL_REQUESTED,     // 취소 요청 중
    REFUNDED              // 환불 완료
}
