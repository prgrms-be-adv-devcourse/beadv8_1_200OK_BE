package com.ok.delivery.application.dto;

import java.util.List;

/**
 * shipment 하나가 될 판매자 묶음이다. 금액은 모두 결제 시점 값이다.
 *
 * @param regionSurcharge 결제에 포함된 도서산간 추가 운임(원)
 */
public record SellerShipmentCommand(
        Long sellerId,
        Long baseShippingFee,
        Long freeShippingThreshold,
        Long regionSurcharge,
        List<ShipmentItemCommand> items
) {
}
