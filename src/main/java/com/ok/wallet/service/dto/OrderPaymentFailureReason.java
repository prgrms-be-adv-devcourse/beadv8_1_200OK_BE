package com.ok.wallet.service.dto;

/**
 * 주문 결제 실패 사유. 지갑 안에서는 enum으로 다루고, 결과 이벤트에는 이름(문자열)으로 실어 보낸다.
 * 값을 바꾸거나 추가하면 주문 쪽과 맞춰야 한다 (WalletPaymentFailedEvent의 reason 계약).
 */
public enum OrderPaymentFailureReason {
    INSUFFICIENT_BALANCE,     // 잔액 부족
    INVALID_PAYMENT_REQUEST   // 결제 금액이 0 이하 등 처리할 수 없는 요청
}
