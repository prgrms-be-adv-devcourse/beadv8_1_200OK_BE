package com.ok.wallet.domain;

// 지갑이 처리한 주문 결제 요청의 결과. 실패는 잔액 부족만 기록한다
// (금액이 잘못된 요청은 다시 와도 결과가 같아 기록하지 않는다).
public enum WalletOrderPaymentStatus {
    PAID,   // 차감 완료
    FAILED  // 잔액 부족으로 차감하지 않음
}
