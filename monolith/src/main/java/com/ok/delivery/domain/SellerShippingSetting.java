package com.ok.delivery.domain;

import java.time.LocalTime;

/**
 * 판매자가 정한 배송 설정 값이다.
 * 사용자 입력이 아니라 설정 조회가 만드는 값이므로, 잘못된 값은 호출한 코드의 버그로 보고 표준 예외를 던진다.
 *
 * @param freeShippingThreshold 판매자별 상품 금액 합계가 이 금액 이상이면 기본 배송비가 0원이다.
 *                              {@code null}이면 무료배송이 없고, 0이면 항상 무료다.
 *                              0을 "무료배송 없음"으로 쓰면 설정이 빠져 0으로 채워질 때 배송비가 조용히 0원이 되므로 따로 표현한다
 * @param cutoffTime            이 시각 전에 결제되면 그날 출고한다
 */
public record SellerShippingSetting(int baseShippingFee, Integer freeShippingThreshold, LocalTime cutoffTime) {

    /**
     * 생성할 때 값을 검증한다.
     *
     * @throws IllegalArgumentException 금액이 음수이거나 마감 시간이 없으면
     */
    public SellerShippingSetting {
        if (baseShippingFee < 0 || (freeShippingThreshold != null && freeShippingThreshold < 0)) {
            throw new IllegalArgumentException("배송비 금액은 0 이상이어야 합니다.");
        }
        if (cutoffTime == null) {
            throw new IllegalArgumentException("출고 마감 시간은 필수입니다.");
        }
    }

    /**
     * 상품 금액 합계가 무료배송 대상인지 판단한다.
     *
     * @param itemAmount 한 판매자의 상품 금액 합계
     */
    public boolean isFreeShipping(long itemAmount) {
        return freeShippingThreshold != null && itemAmount >= freeShippingThreshold;
    }
}
