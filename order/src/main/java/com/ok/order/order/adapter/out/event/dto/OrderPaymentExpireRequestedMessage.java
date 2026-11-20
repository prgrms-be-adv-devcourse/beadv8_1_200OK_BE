package com.ok.order.order.adapter.out.event.dto;

/**
 * {@code order.order-payment-expire-requested.v1} 토픽에 보내는 결제 만료 요청. 필드 이름·타입이 지갑과의 약속이다(#656).
 * 지갑은 이 클래스가 아니라 같은 JSON 모양의 자기 DTO로 읽는다. 필드를 바꾸면 지갑도 같이 바꿔야 한다.
 * 지갑은 이미 결제했으면 결제 완료를 다시 보내고, 아니면 실패(PAYMENT_EXPIRED)를 기록한 뒤 결제 실패를 보낸다.
 *
 * @param totalAmount 결제 요청한 금액(원). 주문 생성 메시지의 금액과 같다. 지갑이 처음 보는 주문이면 만료 실패를 기록하는 데 쓴다
 */
public record OrderPaymentExpireRequestedMessage(
        Long orderId,
        Long buyerId,
        Long totalAmount
) {
}
