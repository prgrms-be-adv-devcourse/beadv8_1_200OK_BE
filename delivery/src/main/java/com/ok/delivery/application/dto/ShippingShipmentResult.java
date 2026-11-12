package com.ok.delivery.application.dto;

import com.ok.delivery.domain.Shipment;

import java.time.LocalDateTime;

/**
 * 택배사에 추적을 요청할 배송 중 shipment의 송장 정보다.
 */
public record ShippingShipmentResult(
        Long shipmentId,
        String carrier,
        String trackingNumber,
        LocalDateTime shippedAt
) {

    public static ShippingShipmentResult from(Shipment shipment) {
        return new ShippingShipmentResult(
                shipment.getId(),
                shipment.getCarrier().name(),
                shipment.getTrackingNumber(),
                shipment.getShippedAt());
    }
}
