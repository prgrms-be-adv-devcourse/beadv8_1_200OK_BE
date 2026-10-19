package com.ok.wallet.client.toss;

/**
 * 토스 승인 호출 결과. 호출하는 쪽은 이 세 가지만 보고 충전 건의 다음 상태를 정한다.
 * 결과가 애매하면 Unknown으로 두어, 승인된 결제를 실패로 처리하거나 승인 안 된 결제를 잔액에 반영하지 않게 한다.
 */
public sealed interface TossConfirmResult {

    /** 승인됨. 잔액에 반영한다. */
    record Approved(String paymentKey) implements TossConfirmResult {
    }

    /** 토스가 명확히 거절함. 충전 건을 실패로 기록한다. */
    record Rejected(String code, String message) implements TossConfirmResult {
    }

    /** 승인 여부를 알 수 없음. 충전 건을 처리 중으로 두고 복구 스케줄러가 토스에 다시 확인한다. */
    record Unknown(String reason) implements TossConfirmResult {
    }
}
