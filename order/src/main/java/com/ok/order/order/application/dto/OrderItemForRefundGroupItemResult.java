package com.ok.order.order.application.dto;

import com.ok.order.order.domain.OrderItem;

// 판매자 묶음 안의 주문 상품 한 줄
public record OrderItemForRefundGroupItemResult(
        Long orderItemId,
        long unitPrice,
        int quantity
) {
    public static OrderItemForRefundGroupItemResult from(OrderItem item) {
        return new OrderItemForRefundGroupItemResult(item.getId(), item.getUnitPrice(), item.getQuantity());
    }
}
