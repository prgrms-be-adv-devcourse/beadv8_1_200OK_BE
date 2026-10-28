package com.ok.delivery.service.dto;

/**
 * shipment에 담을 주문 상품 항목이다. 상품명, 옵션명, 단가는 결제 시점 값이다.
 */
public record ShipmentItemCommand(
        Long orderItemId,
        String productName,
        String optionName,
        Integer quantity,
        Long unitPrice
) {
}
