package com.ok.order.order.application.dto;

import com.ok.order.order.domain.OrderSellerGroup;

import java.util.List;

// 환불이 배송비를 돌려줄지·뺄지 판단할 때 쓰는 판매자 묶음 값. 묶음의 모든 주문 상품을 함께 담는다
public record OrderItemForRefundSellerGroupResult(
        long shippingFee,
        long remoteAreaFee,
        long baseShippingFee,
        Long freeShippingThreshold,   // null이면 무료배송 없음
        List<OrderItemForRefundGroupItemResult> items
) {
    public static OrderItemForRefundSellerGroupResult from(OrderSellerGroup group) {
        return new OrderItemForRefundSellerGroupResult(
                group.getShippingFee(),
                group.getRemoteAreaFee(),
                group.getBaseShippingFee(),
                group.getFreeShippingThreshold(),
                group.getItems().stream().map(OrderItemForRefundGroupItemResult::from).toList()
        );
    }
}
