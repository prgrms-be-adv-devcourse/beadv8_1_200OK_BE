package com.ok.wallet.domain;

// 판매자 지갑 원장 기록 사유, 금액은 항상 양수로 저장하고 증감 방향은 이 유형으로 구분
public enum SellerWalletLedgerType {
    PAYOUT    // 정산금 입금: 잔액 증가
}
