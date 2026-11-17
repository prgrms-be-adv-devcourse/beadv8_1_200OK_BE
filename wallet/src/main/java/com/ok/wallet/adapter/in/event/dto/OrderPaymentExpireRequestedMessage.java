package com.ok.wallet.adapter.in.event.dto;

/**
 * 주문이 Kafka로 보내는 결제 만료 요청을 지갑이 읽는 모양. 필드 이름·타입은 주문과의 약속이다(#656).
 * 주문 클래스를 import하지 않고 같은 JSON 모양의 지갑 DTO로 읽는다.
 * 값이 비었는지는 서비스가 검사한다. 빠진 금액을 0으로 읽어 버리지 않도록 totalAmount는 Long으로 받는다.
 *
 * @param totalAmount 결제 요청한 금액(원). 주문 생성 메시지의 금액과 같다. 처음 보는 주문이면 이 금액으로 만료를 기록한다
 */
public record OrderPaymentExpireRequestedMessage(
        Long orderId,
        Long buyerId,
        Long totalAmount
) {
}
