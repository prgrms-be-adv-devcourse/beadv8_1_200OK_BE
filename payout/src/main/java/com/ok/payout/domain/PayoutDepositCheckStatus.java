package com.ok.payout.domain;

// 정산·지갑 대사 줄의 상태 (#619)
public enum PayoutDepositCheckStatus {
    // 아직 확인이 끝나지 않음. 매일 대사가 지갑에 물음
    WAITING,
    // 지갑 원장에 같은 판매자·금액으로 입금됨. 다시 묻지 않음
    MATCHED,
    // 사람이 봐야 함. 이유는 PayoutDepositMismatchReason. 다시 묻지 않고 대사가 자동으로 고치지 않음
    NEEDS_REVIEW
}
