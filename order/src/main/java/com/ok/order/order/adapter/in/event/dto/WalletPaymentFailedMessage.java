package com.ok.order.order.adapter.in.event.dto;

/**
 * 지갑이 {@code wallet.wallet-payment-failed.v1}로 보내는 결제 실패를 주문이 읽는 모양. 필드 이름·타입은 지갑과의 약속이다(#602, 지갑 #640).
 * 지갑 클래스를 import하지 않고 같은 JSON 모양의 주문 DTO로 읽는다.
 *
 * @param reason 실패 사유. {@code INSUFFICIENT_BALANCE}(잔액 부족), {@code INVALID_PAYMENT_REQUEST}(처리할 수 없는 금액)
 */
public record WalletPaymentFailedMessage(
        Long orderId,
        Long buyerId,
        String reason
) {
}
