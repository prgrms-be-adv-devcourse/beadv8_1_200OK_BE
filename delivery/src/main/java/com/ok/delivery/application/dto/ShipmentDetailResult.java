package com.ok.delivery.application.dto;

import com.ok.delivery.domain.Shipment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 배송 상세다. 구매자와 판매자 상세 조회가 같은 형태를 쓴다.
 *
 * @param carrier               택배사. 출고 전이면 {@code null}
 * @param trackingNumber        송장번호. 출고 전이면 {@code null}
 * @param address               현재 배송지. 배송지 변경이 승인되면 바뀐다
 * @param chargedShippingFee    결제 때 낸 배송비(원)
 * @param additionalShippingFee 승인된 배송지 변경으로 판매자에게 따로 낼 추가 운임 합계(원). 없으면 0
 * @param latestAddressChange   가장 최근 배송지 변경 요청. 요청한 적이 없으면 {@code null}
 */
public record ShipmentDetailResult(
        Long shipmentId,
        Long orderId,
        String status,
        String carrier,
        String trackingNumber,
        AddressResult address,
        String regionGroup,
        int chargedShippingFee,
        long additionalShippingFee,
        LocalDate estimatedShipDate,
        LocalDate estimatedDeliveryStart,
        LocalDate estimatedDeliveryEnd,
        boolean delayed,
        LocalDateTime createdAt,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt,
        List<ShipmentItemResult> items,
        LatestAddressChangeResult latestAddressChange
) {

    public static ShipmentDetailResult of(Shipment shipment, long additionalShippingFee, LatestAddressChangeResult latestAddressChange) {
        return new ShipmentDetailResult(
                shipment.getId(),
                shipment.getOrderId(),
                shipment.getStatus().name(),
                shipment.getCarrier() == null ? null : shipment.getCarrier().name(),
                shipment.getTrackingNumber(),
                AddressResult.from(shipment.getAddress()),
                shipment.getRegionGroup(),
                shipment.getChargedShippingFee(),
                additionalShippingFee,
                shipment.getEstimatedShipDate(),
                shipment.getEstimatedDeliveryStart(),
                shipment.getEstimatedDeliveryEnd(),
                shipment.isDelayed(),
                shipment.getCreatedAt(),
                shipment.getShippedAt(),
                shipment.getDeliveredAt(),
                shipment.getCompletedAt(),
                shipment.getCancelledAt(),
                shipment.getItems().stream().map(ShipmentItemResult::from).toList(),
                latestAddressChange);
    }
}
