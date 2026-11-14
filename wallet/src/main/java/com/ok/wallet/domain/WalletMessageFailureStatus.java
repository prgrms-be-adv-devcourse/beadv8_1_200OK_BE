package com.ok.wallet.domain;

// 실패 기록의 처리 상태. 지금은 사람이 원인을 고치고 처리한 뒤 해결로 바꾼다(자동 재처리는 전환 뒤, #579)
public enum WalletMessageFailureStatus {
    PENDING,  // 대기: 아직 처리하지 않음
    RESOLVED  // 해결: 다시 처리했거나 처리하지 않아도 되는 것으로 확인함
}
