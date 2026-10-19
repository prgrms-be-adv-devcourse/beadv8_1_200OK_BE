package com.ok.order.order.domain;

public record OrderLine(
        Long productId,
        Long productOptionId,
        Long sellerId,
        String productName,
        String productOptionName,
        long unitPrice,
        int quantity
) {
}
