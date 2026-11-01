package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.CheckoutSellerGroupResult;

import java.util.List;

public record CheckoutSellerGroupResponse(
        Long sellerId,
        List<CheckoutItemResponse> items
) {
    public static CheckoutSellerGroupResponse from(CheckoutSellerGroupResult result) {
        return new CheckoutSellerGroupResponse(
                result.sellerId(),
                result.items().stream().map(CheckoutItemResponse::from).toList()
        );
    }
}