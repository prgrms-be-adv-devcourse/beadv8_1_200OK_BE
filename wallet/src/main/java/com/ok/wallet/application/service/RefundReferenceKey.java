package com.ok.wallet.application.service;

/**
 * 환불 반환 원장의 근거 키 규칙(REFUND:{refundId}).
 * 주문 상품 ID가 아니라 환불 ID를 쓴다. 같은 주문 상품을 나눠 환불하면 주문 상품 ID로는 두 번째 반환이 유니크 제약에 막힌다.
 */
final class RefundReferenceKey {
    private static final String PREFIX = "REFUND:";

    private RefundReferenceKey() {
    }

    static String of(Long refundId) {
        return PREFIX + refundId;
    }
}
