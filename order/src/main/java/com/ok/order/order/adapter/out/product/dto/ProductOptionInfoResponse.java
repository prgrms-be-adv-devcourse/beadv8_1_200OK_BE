package com.ok.order.order.adapter.out.product.dto;

import com.ok.order.order.application.dto.ProductOption;

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

    public ProductOption toProductOption() {
        return new ProductOption(productId, optionId, sellerId, productName, optionName, salePrice, availableStock, purchasable);
    }
}
