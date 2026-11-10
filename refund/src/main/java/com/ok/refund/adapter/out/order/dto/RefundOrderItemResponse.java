package com.ok.refund.adapter.out.order.dto;

import java.util.List;

/**
 * 주문 모듈 {@code GET /internal/v1/orders/{orderId}/items/{orderItemId}} 응답 (요청서 기준 가정).
 * 요청서의 {@code refundedQuantity}는 받지 않는다. 주문은 처리 중인 환불을 몰라 환불이 직접 집계한다(#410 설계 8장).
 *
 * @param sellerGroup 이 주문 상품이 속한 판매자 묶음의 배송비 값. 배송비를 돌려줄지·뺄지 판단할 때 쓴다(#410)
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
        SellerGroup sellerGroup
) {

    /**
     * 판매자 묶음 값. 요청서 ①의 {@code sellerGroup} 모양 그대로다.
     *
     * @param shippingFee           구매자가 낸 묶음 배송비 (원). 무료배송이면 0
     * @param remoteAreaFee         구매자가 낸 도서산간 운임 (원)
     * @param baseShippingFee       주문 시점 판매자 기본 배송비 (원)
     * @param freeShippingThreshold 주문 시점 무료배송 기준 금액 (원). 기준이 없으면 {@code null}
     */
    public record SellerGroup(
            long shippingFee,
            long remoteAreaFee,
            long baseShippingFee,
            Long freeShippingThreshold,
            List<Item> items
    ) {
    }

    /**
     * 묶음 안의 주문 상품 한 줄.
     *
     * @param unitPrice 주문 시점 단가 (원)
     * @param quantity  산 개수
     */
    public record Item(Long orderItemId, long unitPrice, int quantity) {
    }
}
