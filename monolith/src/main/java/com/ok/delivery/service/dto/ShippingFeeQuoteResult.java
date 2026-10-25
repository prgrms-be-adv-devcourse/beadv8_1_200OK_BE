package com.ok.delivery.service.dto;

import java.util.List;

/**
 * 배송비 견적 결과다.
 *
 * @param regionGroup      배송지의 지역 그룹 코드. 도서산간이 아니면 {@code GENERAL}
 * @param sellers          판매자별 견적. 요청한 판매자 순서와 같다
 * @param totalShippingFee 모든 판매자의 배송비 합계(원)
 */
public record ShippingFeeQuoteResult(String regionGroup, List<SellerShippingQuoteResult> sellers, int totalShippingFee) {
}
