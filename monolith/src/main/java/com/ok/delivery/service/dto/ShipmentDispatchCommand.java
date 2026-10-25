package com.ok.delivery.service.dto;

public record ShipmentDispatchCommand(
        Long shipmentId,
        Long sellerId,
        String carrier,
        String trackingNumber
) {
}
