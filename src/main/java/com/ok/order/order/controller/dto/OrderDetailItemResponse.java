package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.OrderDetailItemResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 상품 (주문 시점 값)")
public record OrderDetailItemResponse(
        @Schema(description = "주문 상품 ID") Long orderItemId,
        @Schema(description = "상품 ID") Long productId,
        @Schema(description = "옵션 ID") Long productOptionId,
        @Schema(description = "주문 시점 상품명") String productName,
        @Schema(description = "주문 시점 옵션명") String productOptionName,
        @Schema(description = "주문 시점 판매가") long unitPrice,
        @Schema(description = "수량") int quantity,
        @Schema(description = "판매가 × 수량") long itemAmount,
        @Schema(description = "상품별 상태 (PAYMENT_PENDING, PAYMENT_FAILED, PREPARING, SHIPPING, DELIVERED, PURCHASE_CONFIRMED, CANCEL_REQUESTED, REFUNDED)", example = "PREPARING") String status
) {
    public static OrderDetailItemResponse from(OrderDetailItemResult result) {
        return new OrderDetailItemResponse(result.orderItemId(), result.productId(), result.productOptionId(),
                result.productName(), result.productOptionName(), result.unitPrice(), result.quantity(),
                result.itemAmount(), result.status());
    }
}