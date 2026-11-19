package com.ok.wallet.application.service;

/**
 * 환불 되돌리기 원장의 근거 키 규칙(REFUND_REVERT:{refundId}).
 * 반환 원장(REFUND:{refundId})과 같은 환불 ID를 쓰고 앞말만 달리해, 한 환불의 반환과 되돌리기를 키로 짝지어 본다.
 */
final class RefundRevertReferenceKey {
    private static final String PREFIX = "REFUND_REVERT:";

    private RefundRevertReferenceKey() {
    }

    static String of(Long refundId) {
        return PREFIX + refundId;
    }

    static Long refundIdOf(String referenceKey) {
        return RefundReferenceKey.refundIdAfter(PREFIX, referenceKey);
    }
}
