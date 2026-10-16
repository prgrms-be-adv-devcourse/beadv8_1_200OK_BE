package com.ok.wallet.domain;

// 충전 건의 상태. 완료와 실패는 끝 상태라 더 바뀌지 않는다.
public enum ChargeStatus {
    PENDING,       // 충전 요청됨, 토스 결제창 진행 전
    IN_PROGRESS,   // 토스 승인 호출 중이거나 결과가 불명확함
    COMPLETED,     // 승인되어 잔액에 반영됨
    FAILED;        // 결제 실패 또는 명확한 승인 실패

    public boolean canTransitionTo(ChargeStatus next) {
        return switch (this) {
            case PENDING -> next == IN_PROGRESS || next == FAILED;
            case IN_PROGRESS -> next == COMPLETED || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}
