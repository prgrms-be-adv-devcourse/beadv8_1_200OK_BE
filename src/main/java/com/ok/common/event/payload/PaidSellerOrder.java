package com.ok.common.event.payload;

import com.ok.common.event.OrderPaidEvent;

import java.util.List;
import java.util.Objects;

/**
 * 결제된 주문에서 한 판매자의 상품과 배송비를 담는다. {@link OrderPaidEvent}의 일부다.
 * 배송비 관련 값은 주문서에서 배송비를 계산할 때 쓴 값을 그대로 담는다. 배송이 판매자 설정을 따로 조회하면
 * 이벤트 처리 전에 판매자가 설정을 바꿨을 때 결제한 금액과 달라지기 때문이다. 배송비 계산은 배송이 한다.
 *
 * @param sellerId              판매자 회원 ID
 * @param baseShippingFee       판매자 설정의 기본 배송비(원). 무료배송이어도 설정 값을 담는다
 * @param freeShippingThreshold 판매자 설정의 무료배송 기준 금액(원)
 * @param regionSurcharge       도서산간 추가 운임(원). 일반 지역이면 0
 * @param items                 이 판매자의 주문 상품 항목. 1개 이상
 */
public record PaidSellerOrder(
        Long sellerId,
        Long baseShippingFee,
        Long freeShippingThreshold,
        Long regionSurcharge,
        List<PaidOrderItem> items
) {

    public PaidSellerOrder {
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        requireNotNegative(baseShippingFee, "baseShippingFee");
        requireNotNegative(freeShippingThreshold, "freeShippingThreshold");
        requireNotNegative(regionSurcharge, "regionSurcharge");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items는 1개 이상이어야 합니다.");
        }
        // 발행 후 목록이 바뀌지 않게 복사한다. List.copyOf는 null 원소도 거부한다
        items = List.copyOf(items);
    }

    private static void requireNotNegative(Long amount, String name) {
        Objects.requireNonNull(amount, name + "는 필수입니다.");
        if (amount < 0) {
            throw new IllegalArgumentException(name + "는 0 이상이어야 합니다.");
        }
    }
}
