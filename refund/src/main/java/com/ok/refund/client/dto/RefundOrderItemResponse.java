package com.ok.refund.client.dto;

/**
 * 주문 모듈 {@code GET /internal/v1/orders/{orderId}/items/{orderItemId}} 응답 (요청서 기준 가정).
 * 요청 단계(#408)에 쓰는 칸만 받는다. 요청서의 배송비 묶음({@code sellerGroup}) 등 나머지 칸은 응답에 와도
 * 모르는 칸이라 버려지고, 쓰는 이슈(#410)에서 이 record에 더한다.
 */
public record RefundOrderItemResponse(
        Long orderId,
        Long orderItemId,
        Long buyerId,
        Long sellerId,
        Long productOptionId,
        String productName,
        String optionName,
        String status,
        long unitPrice,
        int quantity
) {
}
