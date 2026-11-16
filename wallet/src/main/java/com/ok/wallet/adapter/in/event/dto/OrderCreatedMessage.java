package com.ok.wallet.adapter.in.event.dto;

/**
 * 주문이 Kafka로 보내는 주문 생성(결제 요청)을 지갑이 읽는 모양. 필드 이름·타입은 주문과의 약속이다(#602).
 * 주문 클래스를 import하지 않고 같은 JSON 모양의 지갑 DTO로 읽는다.
 * 값이 비었는지는 DTO가 아니라 서비스가 검사한다. 빠진 금액을 0으로 읽어 버리지 않도록 totalAmount는 Long으로 받는다.
 *
 * @param totalAmount  결제할 금액(원). 상품 + 배송비 + 도서산간 운임
 * @param orderSummary 이용 내역에 보일 주문 요약. 첫 상품 이름 + 나머지 상품 개수(예: "사과 외 3건")이고 주문 시점의 이름이다.
 *                     지금은 받기만 하고 저장·표시는 #609에서 한다. 결제에 쓰지 않아 비었거나 이상해도 결제는 한다
 */
public record OrderCreatedMessage(
        Long orderId,
        Long buyerId,
        Long totalAmount,
        String orderSummary
) {
}
