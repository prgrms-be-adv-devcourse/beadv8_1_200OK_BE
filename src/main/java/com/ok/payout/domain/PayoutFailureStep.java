package com.ok.payout.domain;

// 정산 실패가 난 Step
// 입력 검증(validate), 임시 정산(draft, #90), 결과 검증(verify, #290)
public enum PayoutFailureStep {
    VALIDATE,
    DRAFT,
    VERIFY
}
