package com.ok.delivery.domain;

import java.util.List;

/**
 * 판매자별 배송비를 계산한다.
 * 배송(송장)이 판매자 단위라서 무료배송 기준도 판매자별 상품 금액 합계로 판단한다.
 * 견적, 주문용 계산, 배송 생성이 모두 이 계산을 써야 금액이 어긋나지 않는다.
 */
public final class ShippingFeePolicy {

    private ShippingFeePolicy() {
    }

    /**
     * 기본 배송비를 계산한다. 상품 금액 합계가 무료배송 기준 이상이면 0원이고, 무료배송 기준이 없으면 항상 기본 배송비다.
     *
     * @param itemAmount 한 판매자의 상품 금액 합계
     * @throws IllegalArgumentException 상품 금액이 음수이거나 배송 설정이 없으면
     */
    public static int baseShippingFee(long itemAmount, SellerShippingSetting setting) {
        if (itemAmount < 0) {
            throw new IllegalArgumentException("상품 금액은 0 이상이어야 합니다.");
        }
        if (setting == null) {
            throw new IllegalArgumentException("판매자 배송 설정은 필수입니다.");
        }
        return setting.isFreeShipping(itemAmount) ? 0 : setting.baseShippingFee();
    }

    /**
     * 판매자 하나의 기본 배송비와 추가 운임을 계산한다.
     * 추가 운임은 무료배송 여부와 상관없이 송장마다 붙는다.
     *
     * @param itemAmount      한 판매자의 상품 금액 합계
     * @param regionSurcharge 배송지 지역 판정 결과의 추가 운임
     * @throws IllegalArgumentException 판매자 ID나 배송 설정이 없거나, 상품 금액이나 추가 운임이 음수면 ({@link SellerShippingFee} 생성자 검증 포함)
     */
    public static SellerShippingFee calculate(Long sellerId, long itemAmount, SellerShippingSetting setting, int regionSurcharge) {
        // 판매자 ID와 추가 운임 검증은 SellerShippingFee 생성자가 한다
        return new SellerShippingFee(sellerId, baseShippingFee(itemAmount, setting), regionSurcharge);
    }

    /**
     * 모든 판매자의 배송비(기본 배송비 + 추가 운임) 합계를 반환한다.
     * 배송비 컬럼({@link Shipment})에 맞춰 {@code int}로 반환하되, 넘치면 틀린 금액 대신 예외로 드러내도록 {@code long}으로 더한다.
     *
     * @throws IllegalArgumentException 목록이 없으면
     * @throws ArithmeticException      합계가 {@code int} 범위를 넘으면
     */
    public static int totalFee(List<SellerShippingFee> fees) {
        if (fees == null) {
            throw new IllegalArgumentException("배송비 목록은 필수입니다.");
        }
        long total = fees.stream()
                .mapToLong(SellerShippingFee::totalFee)
                .sum();
        return Math.toIntExact(total);
    }
}
