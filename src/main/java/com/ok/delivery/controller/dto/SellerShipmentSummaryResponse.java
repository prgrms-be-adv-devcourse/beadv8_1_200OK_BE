package com.ok.delivery.controller.dto;

import com.ok.delivery.service.dto.SellerShipmentSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "판매자 출고 목록의 한 건")
public record SellerShipmentSummaryResponse(

        @Schema(description = "shipment ID", example = "1")
        Long shipmentId,

        @Schema(description = "주문 ID", example = "1")
        Long orderId,

        @Schema(description = "배송 상태 (PREPARING, SHIPPING, DELIVERED, COMPLETED, CANCELLED)", example = "PREPARING")
        String status,

        @Schema(description = "현재 배송지의 수령인 이름", example = "홍길동")
        String recipientName,

        @Schema(description = "현재 배송지의 지역 그룹 (도서산간이 아니면 GENERAL)", example = "GENERAL")
        String regionGroup,

        @Schema(description = "출고 예정일", example = "2026-10-20")
        LocalDate estimatedShipDate,

        @Schema(description = "택배사. 출고 전이면 null", example = "CJ_LOGISTICS")
        String carrier,

        @Schema(description = "송장번호. 출고 전이면 null", example = "1234567890")
        String trackingNumber,

        @Schema(description = "도착 예정일을 넘겨 배송이 지연됐는지", example = "false")
        boolean delayed,

        @Schema(description = "배송 생성 시각 (결제 완료 직후)", example = "2026-10-20T14:30:00")
        LocalDateTime createdAt,

        @Schema(description = "대표 상품명 (첫 번째 상품)", example = "무선 키보드")
        String representativeProductName,

        @Schema(description = "대표 상품을 뺀 나머지 상품 수", example = "2")
        int otherItemCount,

        @Schema(description = "처리 대기 중인 배송지 변경 요청이 있는지. 있으면 출고 전에 승인·거절해야 한다", example = "false")
        boolean addressChangePending
) {

    public static SellerShipmentSummaryResponse from(SellerShipmentSummaryResult result) {
        return new SellerShipmentSummaryResponse(
                result.shipmentId(),
                result.orderId(),
                result.status(),
                result.recipientName(),
                result.regionGroup(),
                result.estimatedShipDate(),
                result.carrier(),
                result.trackingNumber(),
                result.delayed(),
                result.createdAt(),
                result.representativeProductName(),
                result.otherItemCount(),
                result.addressChangePending());
    }
}
