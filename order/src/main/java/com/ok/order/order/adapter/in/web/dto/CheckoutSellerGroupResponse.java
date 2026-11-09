package com.ok.order.order.adapter.in.web.dto;

import com.ok.order.order.application.dto.CheckoutSellerGroupResult;

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