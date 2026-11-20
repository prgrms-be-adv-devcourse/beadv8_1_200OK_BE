package com.ok.wallet.application.service;

import lombok.extern.slf4j.Slf4j;

/**
 * 환불 반환 원장의 근거 키 규칙(REFUND:{refundId}).
 * 주문 상품 ID가 아니라 환불 ID를 쓴다. 같은 주문 상품을 나눠 환불하면 주문 상품 ID로는 두 번째 반환이 유니크 제약에 막힌다.
 */
@Slf4j
final class RefundReferenceKey {
    private static final String PREFIX = "REFUND:";

    private RefundReferenceKey() {
    }

    static String of(Long refundId) {
        return PREFIX + refundId;
    }

    static Long refundIdOf(String referenceKey) {
        return refundIdAfter(PREFIX, referenceKey);
    }

    /**
     * 앞말 뒤의 환불 ID를 꺼낸다. 형식이 다르면 null을 돌려준다.
     * 원장 한 건의 키가 잘못돼도 이용 내역 목록 전체가 실패하지 않도록 예외를 던지지 않고 경고만 남긴다.
     * 되돌리기 원장도 같은 환불 ID를 앞말만 달리해 쓰므로 함께 쓴다.
     */
    static Long refundIdAfter(String prefix, String referenceKey) {
        if (referenceKey == null || !referenceKey.startsWith(prefix)) {
            log.warn("[LEDGER_REFUND_KEY_INVALID] 환불 원장의 근거 키 형식이 다릅니다. referenceKey={}", referenceKey);
            return null;
        }
        try {
            return Long.valueOf(referenceKey.substring(prefix.length()));
        } catch (NumberFormatException e) {
            log.warn("[LEDGER_REFUND_KEY_INVALID] 환불 원장의 근거 키에서 환불 ID를 읽을 수 없습니다. referenceKey={}", referenceKey);
            return null;
        }
    }
}
