package com.ok.order.order.service.dto;

import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;

import java.time.LocalDateTime;
import java.util.List;

// 주문 목록 한 줄
public record OrderSummaryResult(
        Long orderId,
        String status,
        LocalDateTime orderedAt,
        String representativeProductName,
        int itemCount,
        long totalAmount
) {
    public static OrderSummaryResult from(Order order) {
        List<OrderItem> items = order.getAllItems();
        return new OrderSummaryResult(
                order.getId(),
                order.getStatus().name(),
                order.getCreatedAt(),
                items.get(0).getProductName(),
                items.size(),
                order.getTotalAmount()
        );
    }
}