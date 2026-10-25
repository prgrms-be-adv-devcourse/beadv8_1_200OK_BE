package com.ok.order.order.service.dto;

import com.ok.order.order.domain.OrderSellerGroup;

import java.util.List;

public record OrderDetailSellerGroupResult(
        Long sellerId,
        long shippingFee,
        long remoteAreaFee,
        List<OrderDetailItemResult> items
) {
    public static OrderDetailSellerGroupResult from(OrderSellerGroup group) {
        return new OrderDetailSellerGroupResult(
                group.getSellerId(),
                group.getShippingFee(),
                group.getRemoteAreaFee(),
                group.getItems().stream().map(OrderDetailItemResult::from).toList()
        );
    }
}