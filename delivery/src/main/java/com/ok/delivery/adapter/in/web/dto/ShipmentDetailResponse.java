package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.ShipmentDetailResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "배송 상세")
public record ShipmentDetailResponse(

        @Schema(description = "shipment ID", example = "1")
        Long shipmentId,

        @Schema(description = "주문 ID", example = "1")
        Long orderId,

        @Schema(description = "배송 상태 (PREPARING, SHIPPING, DELIVERED, COMPLETED, CANCELLED)", example = "PREPARING")
        String status,

        @Schema(description = "택배사. 출고 전이면 null", example = "CJ_LOGISTICS")
        String carrier,

        @Schema(description = "송장번호. 출고 전이면 null", example = "1234567890")
        String trackingNumber,

        @Schema(description = "현재 배송지. 배송지 변경이 승인되면 바뀌고, 주문의 배송지는 바뀌지 않는다")
        AddressResponse address,

        @Schema(description = "지역 그룹 (도서산간이 아니면 GENERAL)", example = "GENERAL")
        String regionGroup,

        @Schema(description = "결제 때 낸 배송비(원)", example = "3000")
        int chargedShippingFee,

        @Schema(description = "승인된 배송지 변경으로 판매자에게 따로 낼 추가 운임 합계(원). 시스템에서 결제하지 않는다. 없으면 0", example = "0")
        long additionalShippingFee,

        @Schema(description = "출고 예정일. 배송지 변경이 승인되면 다시 계산된다", example = "2026-10-20")
        LocalDate estimatedShipDate,

        @Schema(description = "도착 예정일 범위 시작", example = "2026-10-22")
        LocalDate estimatedDeliveryStart,

        @Schema(description = "도착 예정일 범위 끝", example = "2026-10-23")
        LocalDate estimatedDeliveryEnd,

        @Schema(description = "도착 예정일을 넘겨 배송이 지연됐는지", example = "false")
        boolean delayed,

        @Schema(description = "배송 생성 시각 (결제 완료 직후)", example = "2026-10-20T14:30:00")
        LocalDateTime createdAt,

        @Schema(description = "출고 시각. 출고 전이면 null", example = "2026-10-21T10:00:00")
        LocalDateTime shippedAt,

        @Schema(description = "배송 완료 시각. 완료 전이면 null", example = "2026-10-23T15:00:00")
        LocalDateTime deliveredAt,

        @Schema(description = "거래 완료 시각. 완료 전이면 null", example = "2026-10-30T09:00:00")
        LocalDateTime completedAt,

        @Schema(description = "배송 취소 시각. 취소되지 않았으면 null", example = "2026-10-20T16:00:00")
        LocalDateTime cancelledAt,

        @Schema(description = "상품 항목")
        List<ShipmentItemResponse> items,

        @Schema(description = "가장 최근 배송지 변경 요청. 요청한 적이 없으면 null")
        LatestAddressChangeResponse latestAddressChange
) {

    public static ShipmentDetailResponse from(ShipmentDetailResult result) {
        return new ShipmentDetailResponse(
                result.shipmentId(),
                result.orderId(),
                result.status(),
                result.carrier(),
                result.trackingNumber(),
                AddressResponse.from(result.address()),
                result.regionGroup(),
                result.chargedShippingFee(),
                result.additionalShippingFee(),
                result.estimatedShipDate(),
                result.estimatedDeliveryStart(),
                result.estimatedDeliveryEnd(),
                result.delayed(),
                result.createdAt(),
                result.shippedAt(),
                result.deliveredAt(),
                result.completedAt(),
                result.cancelledAt(),
                result.items().stream().map(ShipmentItemResponse::from).toList(),
                result.latestAddressChange() == null ? null : LatestAddressChangeResponse.from(result.latestAddressChange()));
    }
}
