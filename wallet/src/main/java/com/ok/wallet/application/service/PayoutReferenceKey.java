package com.ok.wallet.application.service;

/**
 * 정산 입금 원장의 근거 키 규칙(PAYOUT:{payoutId}). 만드는 곳(정산금 입금)과 찾는 곳(정산 입금 확인)이 같은 규칙을 쓰도록 모은다.
 */
final class PayoutReferenceKey {
    private static final String PREFIX = "PAYOUT:";

    private PayoutReferenceKey() {
    }

    static String of(Long payoutId) {
        return PREFIX + payoutId;
    }
}
