package com.ok.order.order.service.dto;

import com.ok.order.order.domain.OrderSellerGroup;

import java.util.List;

public record CheckoutSellerGroupResult(
        Long sellerId,
        long shippingFee,
        List<CheckoutItemResult> items
) {
    public static CheckoutSellerGroupResult from(OrderSellerGroup group) {
        return new CheckoutSellerGroupResult(
                group.getSellerId(),
                group.getShippingFee(),
                group.getItems().stream().map(CheckoutItemResult::from).toList()
        );
    }
}