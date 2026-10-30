package com.ok.delivery.application.dto;

public record ShipmentDispatchCommand(
        Long shipmentId,
        Long sellerId,
        String carrier,
        String trackingNumber
) {
}
