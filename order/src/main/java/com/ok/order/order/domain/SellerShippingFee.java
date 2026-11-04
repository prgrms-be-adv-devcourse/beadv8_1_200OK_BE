package com.ok.order.order.domain;

/**
 * 판매자 묶음 하나에 붙는 배송 금액과, 계산에 쓴 판매자 배송 설정이다. 배송 모듈이 계산한 값이다.
 * 금액 네 개를 메서드 인자로 나란히 넘기면 순서가 바뀌어도 컴파일러가 잡지 못해 하나로 묶었다.
 *
 * @param baseShippingFee       판매자 설정의 기본 배송비(원). 무료배송이어도 설정 값이다
 * @param freeShippingThreshold 판매자 설정의 무료배송 기준 금액(원). {@code null}이면 무료배송이 없다
 * @param shippingFee           무료배송을 적용한 뒤 구매자가 낼 기본 배송비(원)
 * @param remoteAreaFee         이 판매자 송장에 붙는 도서산간 추가 운임(원)
 */
public record SellerShippingFee(
        long baseShippingFee,
        Long freeShippingThreshold,
        long shippingFee,
        long remoteAreaFee
) {

    /**
     * 생성할 때 값을 검증한다.
     * 배송 모듈이 계산한 값이라 잘못된 값은 연동 버그로 보고 표준 예외를 던진다.
     *
     * @throws IllegalArgumentException 금액이 음수면
     */
    public SellerShippingFee {
        if (baseShippingFee < 0 || shippingFee < 0 || remoteAreaFee < 0
                || (freeShippingThreshold != null && freeShippingThreshold < 0)) {
            throw new IllegalArgumentException("배송 금액은 0 이상이어야 합니다.");
        }
    }
}
