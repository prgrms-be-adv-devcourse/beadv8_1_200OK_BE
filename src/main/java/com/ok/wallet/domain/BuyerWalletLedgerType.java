package com.ok.wallet.domain;

// 원장 기록 사유, 금액은 항상 양수로 저장하고 증감 방향은 이 유형으로 구분함
public enum BuyerWalletLedgerType {
    CHARGE,   // 충전: 잔액 증가
    PAYMENT,  // 결제: 잔액 감소
    REFUND    // 환불·반환: 잔액 증가
}
