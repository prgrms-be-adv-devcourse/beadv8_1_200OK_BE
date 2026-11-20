package com.ok.wallet.domain;

/** 지갑이 처리한 환불 되돌리기 요청의 결과 */
public enum WalletOrderRefundRevertStatus {
    REVERTED, // 돌려준 예치금을 다시 가져옴. 돌려준 적 없는 환불도 가져올 돈이 없어 이 결과로 답한다
    FAILED    // 가져오지 못함
}
