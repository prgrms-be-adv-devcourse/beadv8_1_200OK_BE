package com.ok.refund.client.dto;

import java.util.List;

/**
 * 주문 모듈 {@code GET /internal/v1/orders/{orderId}/items/{orderItemId}} 응답(요청서 9.1 기준 가정).
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

    public record SellerGroup(long shippingFee, long remoteAreaFee, List<Item> items) {
    }

    public record Item(Long orderItemId, int quantity) {
    }
}
