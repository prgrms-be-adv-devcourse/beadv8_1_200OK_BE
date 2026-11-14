package com.ok.wallet.adapter.in.event.dto;

/**
 * 환불이 Kafka로 보내는 예치금 반환 요청을 지갑이 읽는 모양. 필드 이름·타입은 환불과의 약속이다(#413).
 * 환불 클래스를 import하지 않고 같은 JSON 모양의 지갑 DTO로 읽는다.
 * 값이 비었는지는 DTO가 아니라 리스너가 검사한다. 빠진 필드를 0으로 읽어 버리지 않도록 amount는 Long으로 받는다.
 *
 * @param sellerId 출고 전 취소라 판매자 지갑은 건드리지 않아 쓰지 않는다
 * @param amount   돌려줄 금액(원). 환불이 계산한 값이다
 */
public record RefundApprovedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        Long sellerId,
        Long amount
) {
}
