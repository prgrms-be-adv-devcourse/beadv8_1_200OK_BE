package com.ok.delivery.service.dto;

import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매자 출고 목록의 한 건이다. 판매자가 출고를 준비하는 데 필요한 값만 담는다.
 *
 * @param recipientName             현재 배송지의 수령인 이름
 * @param regionGroup               현재 배송지의 지역 그룹. 도서산간이 아니면 {@code GENERAL}
 * @param carrier                   택배사. 출고 전이면 {@code null}
 * @param trackingNumber            송장번호. 출고 전이면 {@code null}
 * @param representativeProductName 첫 번째 상품 항목의 상품명
 * @param otherItemCount            대표 상품을 뺀 나머지 상품 항목 수
 * @param addressChangePending      처리 대기 중인 배송지 변경 요청이 있는지
 */
public record SellerShipmentSummaryResult(
        Long shipmentId,
        Long orderId,
        String status,
        String recipientName,
        String regionGroup,
        LocalDate estimatedShipDate,
        String carrier,
        String trackingNumber,
        boolean delayed,
        LocalDateTime createdAt,
        String representativeProductName,
        int otherItemCount,
        boolean addressChangePending
) {

    /**
     * shipment와 상품 항목으로 출고 목록 한 건을 만든다.
     *
     * @param items 이 shipment의 상품 항목. 만든 순서대로 1개 이상이다
     * @throws IllegalArgumentException 상품 항목이 없으면
     */
    public static SellerShipmentSummaryResult of(Shipment shipment, List<ShipmentItem> items, boolean addressChangePending) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("shipment에 상품 항목이 없습니다. shipmentId=" + shipment.getId());
        }
        return new SellerShipmentSummaryResult(
                shipment.getId(),
                shipment.getOrderId(),
                shipment.getStatus().name(),
                shipment.getAddress().getRecipientName(),
                shipment.getRegionGroup(),
                shipment.getEstimatedShipDate(),
                shipment.getCarrier() == null ? null : shipment.getCarrier().name(),
                shipment.getTrackingNumber(),
                shipment.isDelayed(),
                shipment.getCreatedAt(),
                items.getFirst().getProductName(),
                items.size() - 1,
                addressChangePending);
    }
}
