package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.ShipmentSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "배송 목록의 한 건")
public record ShipmentSummaryResponse(

        @Schema(description = "shipment ID", example = "1")
        Long shipmentId,

        @Schema(description = "주문 ID", example = "1")
        Long orderId,

        @Schema(description = "배송 상태 (PREPARING, SHIPPING, DELIVERED, COMPLETED, CANCELLED)", example = "SHIPPING")
        String status,

        @Schema(description = "택배사. 출고 전이면 null", example = "CJ_LOGISTICS")
        String carrier,

        @Schema(description = "송장번호. 출고 전이면 null", example = "1234567890")
        String trackingNumber,

        @Schema(description = "도착 예정일 범위 시작", example = "2026-10-22")
        LocalDate estimatedDeliveryStart,

        @Schema(description = "도착 예정일 범위 끝", example = "2026-10-23")
        LocalDate estimatedDeliveryEnd,

        @Schema(description = "도착 예정일을 넘겨 배송이 지연됐는지", example = "false")
        boolean delayed,

        @Schema(description = "배송 생성 시각 (결제 완료 직후)", example = "2026-10-20T14:30:00")
        LocalDateTime createdAt,

        @Schema(description = "대표 상품명 (첫 번째 상품)", example = "무선 키보드")
        String representativeProductName,

        @Schema(description = "대표 상품을 뺀 나머지 상품 수. '무선 키보드 외 2건'처럼 보여 줄 때 쓴다", example = "2")
        int otherItemCount
) {

    public static ShipmentSummaryResponse from(ShipmentSummaryResult result) {
        return new ShipmentSummaryResponse(
                result.shipmentId(),
                result.orderId(),
                result.status(),
                result.carrier(),
                result.trackingNumber(),
                result.estimatedDeliveryStart(),
                result.estimatedDeliveryEnd(),
                result.delayed(),
                result.createdAt(),
                result.representativeProductName(),
                result.otherItemCount());
    }
}
