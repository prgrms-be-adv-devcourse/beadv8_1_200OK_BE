package com.ok.order.order.domain;

public enum OrderStatus {
    PAYMENT_PENDING,   // 결제 대기
    PAID,              // 결제 완료
    PAYMENT_FAILED,    // 결제 실패
    CANCELED           // 취소 (결제까지 가지 못하고 무산된 주문. 구매자의 취소·환불은 환불 모듈이 따로 다룬다)
}
