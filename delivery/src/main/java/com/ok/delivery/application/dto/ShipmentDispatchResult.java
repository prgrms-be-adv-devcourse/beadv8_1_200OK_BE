package com.ok.delivery.application.dto;

import com.ok.delivery.domain.Shipment;

import java.time.LocalDateTime;

/**
 * 판매자 출고 결과를 담는다.
 *
 * @param outboxId 출고와 함께 남긴 배송 시작 outbox 줄 id. 커밋 뒤 바로 보낼 줄을 Facade에 알리는 데 쓰고 응답에는 담지 않는다
 */
public record ShipmentDispatchResult(
        Long shipmentId,
        String status,
        String carrier,
        String trackingNumber,
        LocalDateTime shippedAt,
        Long outboxId
) {

    public static ShipmentDispatchResult of(Shipment shipment, Long outboxId) {
        return new ShipmentDispatchResult(
                shipment.getId(),
                shipment.getStatus().name(),
                shipment.getCarrier().name(),
                shipment.getTrackingNumber(),
                shipment.getShippedAt(),
                outboxId);
    }
}
