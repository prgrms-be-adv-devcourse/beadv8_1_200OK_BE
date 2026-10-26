package com.ok.payout.domain;

// 정산 실패가 난 Step
// 입력 검증(validate)과 임시 정산(draft, #90)이 있음. verify 누락(NOT_IN_DRAFT)은 후속 이슈에서 추가
public enum PayoutFailureStep {
    VALIDATE,
    DRAFT
}
