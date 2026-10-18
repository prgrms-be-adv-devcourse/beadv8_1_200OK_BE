package com.ok.delivery.domain;

/**
 * 판매자(송장) 하나의 배송비 계산 결과다.
 */
public record SellerShippingFee(Long sellerId, int baseShippingFee, int regionSurcharge) {

    public int totalFee() {
        return baseShippingFee + regionSurcharge;
    }
}
