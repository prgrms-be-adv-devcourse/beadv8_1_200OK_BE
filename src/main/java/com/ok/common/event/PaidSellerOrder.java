package com.ok.common.event;

import java.util.List;
import java.util.Objects;

/**
 * 결제된 주문에서 한 판매자의 상품과 배송비를 담는다. {@link OrderPaidEvent}의 일부다.
 * 배송비 관련 값은 주문서에서 배송비를 계산할 때 쓴 값을 그대로 담는다. 배송이 판매자 설정을 따로 조회하면
 * 이벤트 처리 전에 판매자가 설정을 바꿨을 때 결제한 금액과 달라지기 때문이다.
 *
 * <p>무료배송 여부는 따로 담지 않는다. 상품 합계({@code unitPrice × quantity})가 {@code freeShippingThreshold} 이상이면 무료배송이고,
 * 결제된 배송비는 (무료배송이면 0, 아니면 {@code baseShippingFee}) + {@code regionSurcharge}이다.
 * 주문은 정산 정책(배송비 전액 판매자)에 맞게 이 합계를 판매자별 배송비로 저장한다.
 *
 * @param baseShippingFee       판매자 설정의 기본 배송비(원). 무료배송이어도 설정 값을 담는다. 일부 취소로 무료배송 기준에 못 미칠 때 안내할 금액이다
 * @param freeShippingThreshold 판매자 설정의 무료배송 기준 금액(원)
 * @param regionSurcharge       도서산간 추가 운임(원). 일반 지역이면 0. 무료배송이어도 부과한다
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
        Objects.requireNonNull(baseShippingFee, "baseShippingFee는 필수입니다.");
        Objects.requireNonNull(freeShippingThreshold, "freeShippingThreshold는 필수입니다.");
        Objects.requireNonNull(regionSurcharge, "regionSurcharge는 필수입니다.");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items는 1개 이상이어야 합니다.");
        }
        // 발행 후 목록이 바뀌지 않게 복사한다. List.copyOf는 null 원소도 거부한다
        items = List.copyOf(items);
    }
}
