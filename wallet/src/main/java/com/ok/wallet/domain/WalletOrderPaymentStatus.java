package com.ok.wallet.domain;

// 지갑이 처리한 주문 결제 요청의 결과. 실패는 잔액 부족과 결제 만료만 기록한다
// (금액이 잘못된 요청은 다시 와도 결과가 같아 기록하지 않는다).
public enum WalletOrderPaymentStatus {
    PAID,    // 차감 완료
    FAILED,  // 잔액 부족으로 차감하지 않음
    EXPIRED  // 결제 요청보다 주문의 결제 만료 요청이 먼저 와서 결제하지 않기로 함. 늦게 온 결제 요청도 차감하지 않는다
}
