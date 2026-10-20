package com.ok.delivery.service.dto;

import com.ok.delivery.domain.Shipment;

import java.time.LocalDateTime;

public record ShipmentDispatchResult(
        Long shipmentId,
        String status,
        String carrier,
        String trackingNumber,
        LocalDateTime shippedAt
) {

    public static ShipmentDispatchResult from(Shipment shipment) {
        return new ShipmentDispatchResult(
                shipment.getId(),
                shipment.getStatus().name(),
                shipment.getCarrier().name(),
                shipment.getTrackingNumber(),
                shipment.getShippedAt());
    }
}
