package com.ok.product;

import java.time.LocalTime;

/**
 * 판매자가 등록한 배송 설정이다.
 * 배송 모듈이 배송비와 출고 예정일을 계산할 때 읽는다.
 *
 * @param freeShippingThreshold 판매자별 상품 금액 합계가 이 금액 이상이면 기본 배송비가 0원이다. {@code null}이면 무료배송이 없고, 0이면 항상 무료다
 * @param cutoffTime            이 시각 전에 결제되면 그날 출고한다
 */
public record SellerShippingPolicy(int baseShippingFee, Integer freeShippingThreshold, LocalTime cutoffTime) {
}
