package com.ok.delivery.application.dto;

import java.time.LocalDate;

/**
 * 판매자(송장) 한 건의 배송비와 예정일 견적이다.
 *
 * @param baseShippingFee  기본 배송비(원). 무료배송 기준 이상이면 0
 * @param regionSurcharge  도서산간 추가 운임(원). 무료배송이어도 붙는다
 * @param shippingFee      기본 배송비와 추가 운임의 합계(원)
 * @param expectedShipDate 출고 예정일
 * @param arrivalStartDate 도착 예정일 범위의 시작
 * @param arrivalEndDate   도착 예정일 범위의 끝
 */
public record SellerShippingQuoteResult(
        Long sellerId,
        int baseShippingFee,
        int regionSurcharge,
        int shippingFee,
        LocalDate expectedShipDate,
        LocalDate arrivalStartDate,
        LocalDate arrivalEndDate
) {
}
