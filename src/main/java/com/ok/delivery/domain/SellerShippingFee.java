package com.ok.delivery.domain;

/**
 * 판매자(송장) 하나의 배송비 계산 결과다.
 */
public record SellerShippingFee(Long sellerId, int baseShippingFee, int regionSurcharge) {

    /**
     * 기본 배송비와 추가 운임의 합계를 반환한다.
     *
     * @throws ArithmeticException 합계가 {@code int} 범위를 넘으면
     */
    public int totalFee() {
        return Math.addExact(baseShippingFee, regionSurcharge);
    }
}
