package com.ok.order.order.adapter.in.web.dto;

import com.ok.order.order.application.dto.OrderItemForRefundGroupItemResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자 묶음 안의 주문 상품 한 줄")
public record OrderItemForRefundGroupItemResponse(

        @Schema(description = "주문 상품 ID", example = "101")
        Long orderItemId,

        @Schema(description = "주문 시점 판매가 (원)", example = "10000")
        long unitPrice,

        @Schema(description = "산 개수", example = "3")
        int quantity
) {
    public static OrderItemForRefundGroupItemResponse from(OrderItemForRefundGroupItemResult result) {
        return new OrderItemForRefundGroupItemResponse(result.orderItemId(), result.unitPrice(), result.quantity());
    }
}
