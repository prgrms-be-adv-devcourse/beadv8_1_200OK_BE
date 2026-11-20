package com.ok.order.order.adapter.in.web.dto;

import com.ok.order.order.application.dto.CheckoutItemResult;

public record CheckoutItemResponse(
        Long productOptionId,
        String productName,
        String productOptionName,
        long unitPrice,
        int quantity,
        long itemAmount
) {
    public static CheckoutItemResponse from(CheckoutItemResult result) {
        return new CheckoutItemResponse(result.productOptionId(), result.productName(),
                result.productOptionName(), result.unitPrice(), result.quantity(), result.itemAmount());
    }
}