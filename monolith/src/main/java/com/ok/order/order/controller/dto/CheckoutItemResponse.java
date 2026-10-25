package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.CheckoutItemResult;

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