package com.ok.payout.adapter.in.event.dto;

import com.ok.payout.application.port.in.PurchaseConfirmedCommand;

/**
 * 주문이 Kafka로 보내는 구매확정 메시지를 정산이 읽는 모양. 필드 뜻은 주문 담당이 정했다(#339 "정산이 받는 JSON 약속").
 * 주문 클래스를 import하지 않고 같은 JSON 모양의 정산 DTO로 읽는다 (컨벤션 2.1).
 */
public record OrderProductConfirmedMessage(
        // 주문 ID
        Long orderId,
        // 주문 상품 ID. 정산의 중복 방지 기준
        Long orderItemId,
        // 판매자 회원 ID
        Long sellerId,
        // 판매가 × 수량(원)
        Long totalItemPrice,
        // 판매자 묶음 배송비(원). 도서산간 운임 포함. 같은 묶음의 상품마다 같은 값이 온다
        Long sellerShippingPrice
) {

    // 값 검사(null, 음수)는 Spring 이벤트로 받을 때와 똑같이 서비스(PayoutCandidateService)가 한다
    public PurchaseConfirmedCommand toCommand() {
        return new PurchaseConfirmedCommand(orderId, orderItemId, sellerId, totalItemPrice, sellerShippingPrice);
    }
}
