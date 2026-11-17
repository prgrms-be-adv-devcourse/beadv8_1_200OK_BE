package com.ok.order.order.adapter.out.event.dto;

/**
 * {@code order.order-created.v1} 토픽에 보내는 주문 생성(결제 요청) 메시지. 필드 이름·타입이 지갑과의 약속이다(#602, 지갑 #640).
 * 지갑은 이 클래스가 아니라 같은 JSON 모양의 자기 DTO로 읽는다. 필드를 바꾸면 지갑도 같이 바꿔야 한다.
 *
 * @param totalAmount  결제할 금액(원). 상품 + 배송비 + 도서산간 운임
 * @param orderSummary 지갑 이용 내역에 보일 주문 요약(예: "사과 외 3건"). 결제에는 쓰지 않는다
 */
public record OrderCreatedMessage(
        Long orderId,
        Long buyerId,
        Long totalAmount,
        String orderSummary
) {
}
