package com.ok.order.order.service.dto;

import com.ok.order.order.domain.OrderItem;

// 결제된 주문 상품 하나 (상품명, 옵션명, 가격은 주문 시점 값)
public record PaidItemMessage(
        Long orderItemId,
        String productName,
        String optionName,
        int quantity,
        long unitPrice
) {
    public static PaidItemMessage from(OrderItem item) {
        return new PaidItemMessage(
                item.getId(),
                item.getProductName(),
                item.getProductOptionName(),
                item.getQuantity(),
                item.getUnitPrice()
        );
    }
}