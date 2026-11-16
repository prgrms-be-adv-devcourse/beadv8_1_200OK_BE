package com.ok.order.order.application.dto;

import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;

// 환불 모듈에 넘길 주문 상품 하나와 그 상품이 속한 판매자 묶음
public record OrderItemForRefundResult(
        Long orderId,
        Long orderItemId,
        Long buyerId,
        Long sellerId,
        Long productOptionId,
        String productName,
        String optionName,
        String status,
        long unitPrice,
        int quantity,
        OrderItemForRefundSellerGroupResult sellerGroup
) {
    public static OrderItemForRefundResult from(Order order, OrderItem item) {
        return new OrderItemForRefundResult(
                order.getId(),
                item.getId(),
                order.getBuyerId(),
                item.getSellerGroup().getSellerId(),
                item.getProductOptionId(),
                item.getProductName(),
                item.getProductOptionName(),
                item.getStatus().name(),
                item.getUnitPrice(),
                item.getQuantity(),
                OrderItemForRefundSellerGroupResult.from(item.getSellerGroup())
        );
    }
}
