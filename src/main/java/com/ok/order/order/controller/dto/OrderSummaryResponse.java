package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.OrderSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "주문 목록 한 줄")
public record OrderSummaryResponse(
        @Schema(description = "주문 ID", example = "15") Long orderId,
        @Schema(description = "주문 상태", example = "PAID") String status,
        @Schema(description = "주문 시각") LocalDateTime orderedAt,
        @Schema(description = "대표 상품명", example = "사과") String representativeProductName,
        @Schema(description = "주문 상품 종류 수", example = "3") int itemCount,
        @Schema(description = "총 결제 금액", example = "23000") long totalAmount
) {
    public static OrderSummaryResponse from(OrderSummaryResult result) {
        return new OrderSummaryResponse(
                result.orderId(), result.status(), result.orderedAt(),
                result.representativeProductName(), result.itemCount(), result.totalAmount());
    }
}