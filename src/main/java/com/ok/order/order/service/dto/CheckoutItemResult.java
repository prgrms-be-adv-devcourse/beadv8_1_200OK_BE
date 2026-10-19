package com.ok.order.order.service.dto;

import com.ok.order.order.domain.OrderItem;

public record CheckoutItemResult(
        Long productOptionId,
        String productName,
        String productOptionName,
        long unitPrice,
        int quantity,
        long itemAmount
) {
    public static CheckoutItemResult from(OrderItem item) {
        return new CheckoutItemResult(item.getProductOptionId(), item.getProductName(),
                item.getProductOptionName(), item.getUnitPrice(), item.getQuantity(), item.getItemAmount());
    }
}