package com.ok.delivery.service.dto;

import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 배송 목록의 한 건이다. 상품은 대표 상품명과 나머지 개수만 담는다.
 *
 * @param carrier                   택배사. 출고 전이면 {@code null}
 * @param trackingNumber            송장번호. 출고 전이면 {@code null}
 * @param representativeProductName 첫 번째 상품 항목의 상품명
 * @param otherItemCount            대표 상품을 뺀 나머지 상품 항목 수
 */
public record ShipmentSummaryResult(
        Long shipmentId,
        Long orderId,
        String status,
        String carrier,
        String trackingNumber,
        LocalDate estimatedDeliveryStart,
        LocalDate estimatedDeliveryEnd,
        boolean delayed,
        LocalDateTime createdAt,
        String representativeProductName,
        int otherItemCount
) {

    /**
     * shipment와 상품 항목으로 목록 한 건을 만든다.
     *
     * @param items 이 shipment의 상품 항목. 만든 순서대로 1개 이상이다
     * @throws IllegalArgumentException 상품 항목이 없으면
     */
    public static ShipmentSummaryResult of(Shipment shipment, List<ShipmentItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("shipment에 상품 항목이 없습니다. shipmentId=" + shipment.getId());
        }
        return new ShipmentSummaryResult(
                shipment.getId(),
                shipment.getOrderId(),
                shipment.getStatus().name(),
                shipment.getCarrier() == null ? null : shipment.getCarrier().name(),
                shipment.getTrackingNumber(),
                shipment.getEstimatedDeliveryStart(),
                shipment.getEstimatedDeliveryEnd(),
                shipment.isDelayed(),
                shipment.getCreatedAt(),
                items.getFirst().getProductName(),
                items.size() - 1);
    }
}
