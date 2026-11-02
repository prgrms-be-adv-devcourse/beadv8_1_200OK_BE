package com.ok.refund.domain;

import java.util.List;

/**
 * 판매자 묶음 하나의 상품 목록과 주문 시점 배송비 정보. 주문 모듈 응답에서 옮겨 담는다.
 *
 * @param shippingFee           주문 때 낸 묶음 배송비 (원). 무료배송이었으면 0
 * @param remoteAreaFee         주문 때 낸 도서산간 추가 운임 (원)
 * @param baseShippingFee       주문 시점 판매자 기본 배송비 (원)
 * @param freeShippingThreshold 주문 시점 무료배송 기준 금액 (원). 판매자가 정하지 않았으면 {@code null}(무료배송 없음)
 */
public record BundleShipping(
        List<BundleItem> items,
        long shippingFee,
        long remoteAreaFee,
        long baseShippingFee,
        Long freeShippingThreshold
) {
}
