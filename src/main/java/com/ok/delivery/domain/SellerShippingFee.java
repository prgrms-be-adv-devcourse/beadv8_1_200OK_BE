package com.ok.delivery.domain;

/**
 * 판매자(송장) 하나의 배송비 계산 결과다.
 * 금액 검증을 생성자에 두어 {@link ShippingFeePolicy}를 거치지 않고 만들어도 잘못된 값이 들어오지 않게 한다.
 */
public record SellerShippingFee(Long sellerId, int baseShippingFee, int regionSurcharge) {

    /**
     * 생성할 때 값을 검증한다.
     *
     * @throws IllegalArgumentException 판매자 ID가 없거나 기본 배송비, 추가 운임이 음수면
     */
    public SellerShippingFee {
        if (sellerId == null) {
            throw new IllegalArgumentException("sellerId는 필수입니다.");
        }
        if (baseShippingFee < 0) {
            throw new IllegalArgumentException("기본 배송비는 0 이상이어야 합니다.");
        }
        if (regionSurcharge < 0) {
            throw new IllegalArgumentException("추가 운임은 0 이상이어야 합니다.");
        }
    }

    /**
     * 기본 배송비와 추가 운임의 합계를 반환한다.
     *
     * @throws ArithmeticException 합계가 {@code int} 범위를 넘으면
     */
    public int totalFee() {
        return Math.addExact(baseShippingFee, regionSurcharge);
    }
}
