package com.ok.wallet.adapter.out.event.dto;

/**
 * {@code wallet.wallet-payment-failed.v1} 토픽에 보내는 메시지. 필드 이름·타입이 주문과의 약속이다(#602).
 * 주문은 이 클래스가 아니라 JSON 모양으로 읽는다(타입 헤더를 싣지 않음). 필드를 바꾸면 주문 쪽도 같이 바꿔야 한다.
 *
 * @param reason 실패 사유. {@code INSUFFICIENT_BALANCE}(잔액 부족), {@code INVALID_PAYMENT_REQUEST}(처리할 수 없는 금액)
 */
public record WalletPaymentFailedMessage(
        Long orderId,
        Long buyerId,
        String reason
) {
}
