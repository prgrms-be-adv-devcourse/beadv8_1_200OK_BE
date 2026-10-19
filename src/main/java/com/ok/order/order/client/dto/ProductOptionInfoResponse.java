package com.ok.order.order.client.dto;

public record ProductOptionInfoResponse(
        Long productId,
        Long optionId,
        Long sellerId,
        String productName,
        String optionName,
        long salePrice,
        int availableStock,
        boolean purchasable
) {
}
