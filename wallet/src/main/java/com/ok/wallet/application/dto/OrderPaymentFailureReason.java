package com.ok.wallet.application.dto;

/**
 * 주문 결제 실패 사유. 지갑 안에서는 enum으로 다루고, 결제 실패 메시지에는 이름(문자열)으로 실어 보낸다.
 * 값을 바꾸거나 추가하면 주문 쪽과 맞춰야 한다 (wallet.wallet-payment-failed.v1의 reason 약속).
 */
public enum OrderPaymentFailureReason {
    INSUFFICIENT_BALANCE,     // 잔액 부족
    INVALID_PAYMENT_REQUEST,  // 결제 금액이 0 이하 등 처리할 수 없는 요청
    PAYMENT_EXPIRED           // 주문이 결제 요청 뒤 결과를 오래 받지 못해 만료를 요청했고, 그때까지 결제하지 않았음
}
