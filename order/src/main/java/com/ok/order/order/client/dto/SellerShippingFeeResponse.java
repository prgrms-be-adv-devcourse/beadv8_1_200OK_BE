package com.ok.order.order.client.dto;

/**
 * 배송 모듈이 계산한 판매자 한 명의 배송비와 계산에 쓴 판매자 설정 값이다.
 *
 * @param baseShippingFee       판매자 설정의 기본 배송비(원). 무료배송이어도 설정 값이다
 * @param freeShippingThreshold 판매자 설정의 무료배송 기준 금액(원). {@code null}이면 무료배송이 없다
 * @param shippingFee           무료배송을 적용한 뒤 구매자가 낼 기본 배송비(원). 도서산간 운임은 포함하지 않는다
 * @param regionSurcharge       이 판매자 송장에 붙는 도서산간 추가 운임(원). 일반 지역이거나 우편번호 없이 요청하면 0
 */
public record SellerShippingFeeResponse(
        Long sellerId,
        long baseShippingFee,
        Long freeShippingThreshold,
        long shippingFee,
        long regionSurcharge
) {
}
