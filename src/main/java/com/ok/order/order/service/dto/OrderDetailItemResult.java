package com.ok.order.order.service.dto;

import com.ok.order.order.domain.OrderItem;

public record OrderDetailItemResult(
        Long orderItemId,
        Long productId,
        Long productOptionId,
        String productName,
        String productOptionName,
        long unitPrice,
        int quantity,
        long itemAmount,
        String status // enum 대신 문자열
) {
    public static OrderDetailItemResult from(OrderItem item) {
        return new OrderDetailItemResult(item.getId(), item.getProductId(), item.getProductOptionId(),
                item.getProductName(), item.getProductOptionName(), item.getUnitPrice(), item.getQuantity(),
                item.getItemAmount(), item.getStatus().name());
    }
}