package com.ok.payout.domain;

// 대사에서 관리자 확인 필요가 된 이유 (#619)
public enum PayoutDepositMismatchReason {
    // 3번 물을 때까지 지갑 원장에 입금이 없음 (한 번이라도 "없음"으로 답함)
    NOT_FOUND,
    // 3번 물을 때까지 지갑이 한 번도 답하지 않음. 정산이 아니라 지갑 수신 장애일 가능성이 큼
    NO_RESPONSE,
    // 입금은 있는데 판매자가 다름 (금액도 다를 수 있음. 판매자가 다르면 돈이 다른 사람에게 간 것이라 이 이유를 앞세움)
    PAYEE_MISMATCH,
    // 같은 판매자에게 입금은 있는데 금액이 다름
    AMOUNT_MISMATCH
}
