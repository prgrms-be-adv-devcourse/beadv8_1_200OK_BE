package com.ok.delivery.application.dto;

import java.util.List;

/**
 * 주문 생성용 판매자별 배송비 계산 결과다.
 *
 * @param sellers 판매자별 배송비. 요청한 판매자 순서와 같다
 */
public record ShippingFeesResult(List<SellerShippingFeeResult> sellers) {
}
