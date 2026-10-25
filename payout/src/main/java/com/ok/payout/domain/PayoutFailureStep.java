package com.ok.payout.domain;

// 정산 실패가 난 Step
// 지금은 입력 검증(validate)만 있음. draft 예외(UNEXPECTED_ERROR)는 #90, verify 누락(NOT_IN_DRAFT)은 후속 이슈에서 추가
public enum PayoutFailureStep {
    VALIDATE
}
