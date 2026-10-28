package com.ok.wallet.service;

import lombok.extern.slf4j.Slf4j;

/**
 * 주문 결제 원장의 근거 키 규칙(PAYMENT:{orderId}). 만드는 곳(결제 차감)과 읽는 곳(이용 내역)이 같은 규칙을 쓰도록 모은다.
 */
@Slf4j
final class PaymentReferenceKey {
    private static final String PREFIX = "PAYMENT:";

    private PaymentReferenceKey() {
    }

    static String of(Long orderId) {
        return PREFIX + orderId;
    }

    /**
     * 근거 키에서 주문 ID를 꺼낸다. 형식이 다르면 null을 돌려준다.
     * 원장 한 건의 키가 잘못돼도 이용 내역 목록 전체가 실패하지 않도록 예외를 던지지 않고 경고만 남긴다.
     */
    static Long orderIdOf(String referenceKey) {
        if (referenceKey == null || !referenceKey.startsWith(PREFIX)) {
            log.warn("[LEDGER_PAYMENT_KEY_INVALID] 결제 원장의 근거 키 형식이 다릅니다. referenceKey={}", referenceKey);
            return null;
        }
        try {
            return Long.valueOf(referenceKey.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            log.warn("[LEDGER_PAYMENT_KEY_INVALID] 결제 원장의 근거 키에서 주문 ID를 읽을 수 없습니다. referenceKey={}", referenceKey);
            return null;
        }
    }
}
