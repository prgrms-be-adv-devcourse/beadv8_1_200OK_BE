package com.ok.wallet.domain;

/**
 * 환불 반환을 거절한 사유. 이름이 그대로 환불에 보내는 실패 사유가 된다.
 * 다시 해도 결과가 같은 경우만 둔다. 환불은 지갑 실패를 받으면 주문 취소를 되돌리므로, 일시적인 문제는 사유로 만들지 않는다.
 */
public enum WalletOrderRefundFailureReason {
    PAYMENT_NOT_FOUND,    // 그 주문·구매자의 성공한 결제 기록이 없음
    REFUND_TOTAL_EXCEEDED // 같은 주문의 반환 합계가 결제 금액을 넘음
}
