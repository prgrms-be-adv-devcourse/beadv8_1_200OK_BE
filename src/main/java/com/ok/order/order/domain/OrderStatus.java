package com.ok.order.order.domain;

public enum OrderStatus {
    PAYMENT_PENDING,   // 결제 대기
    PAID,              // 결제 완료
    PAYMENT_FAILED,    // 결제 실패
    CANCELED           // 취소
}
