package com.ok.refund.client.dto;

import java.util.List;

/**
 * 주문 모듈 {@code GET /internal/v1/orders/{orderId}/items/{orderItemId}} 응답(요청서 9.1 기준 가정).
 *
 * @param refundedQuantity 주문이 기록한 취소 완료 개수. 요청서 약속대로 받을 칸은 두지만 계산에는 쓰지 않는다.
 *                         주문은 환불 처리의 ORDER 단계에서야 이 값을 늘려 처리 중인 환불이 빠져 있으므로,
 *                         남은 개수는 환불 테이블(처리 중 + 완료)로 센다
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
        int quantity,
        int refundedQuantity,
        SellerGroup sellerGroup
) {

    /**
     * @param baseShippingFee       주문 시점 판매자 기본 배송비 (원)
     * @param freeShippingThreshold 주문 시점 무료배송 기준 금액 (원). 판매자가 정하지 않았으면 {@code null}(무료배송 없음)
     */
    public record SellerGroup(
            long shippingFee,
            long remoteAreaFee,
            long baseShippingFee,
            Long freeShippingThreshold,
            List<Item> items
    ) {
    }

    public record Item(Long orderItemId, long unitPrice, int quantity) {
    }
}
