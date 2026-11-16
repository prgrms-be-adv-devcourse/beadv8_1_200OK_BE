package com.ok.wallet.domain;

/** 지갑이 처리한 환불 반환 요청의 결과 */
public enum WalletOrderRefundStatus {
    COMPLETED, // 구매자 지갑에 돌려줌
    FAILED     // 돌려주지 않고 거절함
}
