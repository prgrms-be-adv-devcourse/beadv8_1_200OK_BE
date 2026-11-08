package com.ok.delivery.application.dto;

/**
 * 판매자 한 명의 배송비와 계산에 쓴 판매자 설정 값이다.
 * 주문이 이 값을 결제 이벤트에 그대로 담고, 배송이 부과 배송비를 다시 계산하므로 설정 값도 함께 돌려준다.
 *
 * @param baseShippingFee       판매자 설정의 기본 배송비(원). 무료배송이어도 설정 값이다.
 *                              견적 결과({@link SellerShippingQuoteResult})의 기본 배송비는 무료배송 적용 후 값이라 뜻이 다르다
 * @param freeShippingThreshold 판매자 설정의 무료배송 기준 금액(원). {@code null}이면 무료배송이 없다
 * @param shippingFee           무료배송을 적용한 뒤의 기본 배송비(원). 추가 운임은 포함하지 않는다
 * @param regionSurcharge       도서산간 추가 운임(원). 일반 지역이면 0
 */
public record SellerShippingFeeResult(
        Long sellerId,
        int baseShippingFee,
        Integer freeShippingThreshold,
        int shippingFee,
        int regionSurcharge
) {
}
