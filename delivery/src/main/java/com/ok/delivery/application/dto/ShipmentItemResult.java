package com.ok.delivery.application.dto;

import com.ok.delivery.domain.ShipmentItem;

/**
 * 배송에 담긴 상품 항목이다. 상품명, 옵션명, 단가는 결제 시점 값이다.
 *
 * @param optionName       옵션명. 없으면 {@code null}
 * @param canceledQuantity 취소 확정된 수량
 * @param confirmed        구매확정 여부
 */
public record ShipmentItemResult(
        Long orderItemId,
        String productName,
        String optionName,
        int quantity,
        int unitPrice,
        int canceledQuantity,
        boolean confirmed
) {

    public static ShipmentItemResult from(ShipmentItem item) {
        return new ShipmentItemResult(
                item.getOrderItemId(),
                item.getProductName(),
                item.getOptionName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getCanceledQuantity(),
                item.isConfirmed());
    }
}
