package com.ok.wallet.domain;

/**
 * 환불 되돌리기를 하지 못한 사유. 이름이 그대로 환불에 보내는 실패 사유가 된다.
 * 다시 해도 결과가 같은 경우만 둔다. 일시적인 문제는 사유로 만들지 않고 예외로 다시 받는다.
 */
public enum WalletOrderRefundRevertFailureReason {
    INSUFFICIENT_BALANCE // 구매자가 돌려받은 돈을 이미 써서 잔액이 모자람. 잔액을 마이너스로 두지 않는다
}
