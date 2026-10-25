package com.ok.delivery.controller.dto;

import com.ok.delivery.service.dto.ShipmentDispatchResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "출고 및 송장 등록 결과")
public record ShipmentDispatchResponse(

        @Schema(description = "shipment ID", example = "1")
        Long shipmentId,

        @Schema(description = "배송 상태 (출고 후 SHIPPING)", example = "SHIPPING")
        String status,

        @Schema(description = "택배사", example = "CJ_LOGISTICS")
        String carrier,

        @Schema(description = "송장번호", example = "1234567890")
        String trackingNumber,

        @Schema(description = "출고 시각", example = "2026-10-20T14:30:00")
        LocalDateTime shippedAt
) {

    public static ShipmentDispatchResponse from(ShipmentDispatchResult result) {
        return new ShipmentDispatchResponse(
                result.shipmentId(),
                result.status(),
                result.carrier(),
                result.trackingNumber(),
                result.shippedAt());
    }
}
