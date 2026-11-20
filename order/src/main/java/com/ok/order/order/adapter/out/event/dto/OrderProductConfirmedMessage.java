package com.ok.order.order.adapter.out.event.dto;

/**
 * {@code order.order-product-confirmed.v1} 토픽에 보내는 구매확정 메시지. 필드 이름·타입이 정산과의 약속이다(#339, #600).
 * 정산은 이 클래스가 아니라 같은 JSON 모양의 자기 DTO로 읽는다. 필드를 바꾸면 정산도 같이 바꿔야 한다.
 *
 * @param orderItemId         주문 상품 ID. 메시지 키이고 정산의 중복 방지 기준이다
 * @param totalItemPrice      판매가 × (산 수량 − 환불 수량)(원)
 * @param sellerShippingPrice 판매자 묶음 배송비 + 환불에서 뺀 배송비(원). 도서산간 운임 포함. 같은 묶음의 상품마다 같은 값이다
 */
public record OrderProductConfirmedMessage(
        Long orderId,
        Long orderItemId,
        Long sellerId,
        Long totalItemPrice,
        Long sellerShippingPrice
) {
}
