package com.ok.order.order.service.dto;

import com.ok.order.order.domain.Order;

import java.util.List;

public record CheckoutResult(
        List<CheckoutSellerGroupResult> sellerGroups,
        long totalProductAmount,
        long totalShippingFee,
        long totalAmount
) {
    public static CheckoutResult from(Order order) {
        return new CheckoutResult(
                order.getSellerGroups().stream().map(CheckoutSellerGroupResult::from).toList(),
                order.getTotalProductAmount(),
                order.getTotalShippingFee(),
                order.getTotalAmount()
        );
    }
}