package com.ok.refund.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 한 주문 안에서 같은 판매자 상품들(판매자 묶음)의 배송비 값. 주문 모듈이 알려 준 주문 시점 값이다.
 * 배송비와 도서산간 운임은 상자(판매자 묶음) 단위로 내므로 상품 한 줄이 아니라 묶음 전체를 봐야 판단할 수 있다.
 *
 * @param shippingFee           구매자가 이 묶음에 낸 기본 배송비 (원). 무료배송이었으면 0
 * @param remoteAreaFee         구매자가 이 묶음에 낸 도서산간 운임 (원)
 * @param baseShippingFee       판매자의 기본 배송비 (원). 무료배송 기준이 깨질 때 환불에서 빼는 금액
 * @param freeShippingThreshold 무료배송 기준 금액 (원). 기준이 없는 판매자는 {@code null}
 * @param items                 묶음의 모든 주문 상품
 * @throws IllegalArgumentException 값이 비었거나 음수거나 같은 상품이 두 번 오면. 주문 모듈이 보낸 값이라 표준 예외로 500을 낸다
 */
public record BundleShipping(
        long shippingFee,
        long remoteAreaFee,
        long baseShippingFee,
        Long freeShippingThreshold,
        List<BundleItem> items
) {

    public BundleShipping {
        if (shippingFee < 0 || remoteAreaFee < 0 || baseShippingFee < 0) {
            throw new IllegalArgumentException("묶음 배송비·도서산간 운임·기본 배송비는 0 이상이어야 합니다.");
        }
        if (freeShippingThreshold != null && freeShippingThreshold < 0) {
            throw new IllegalArgumentException("무료배송 기준 금액은 0 이상이어야 합니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("묶음 상품 목록은 비어 있을 수 없습니다.");
        }
        // 같은 상품이 두 줄로 오면 주문 개수를 두 번 세어 "전부 취소"가 영영 되지 않는다
        Set<Long> seen = new HashSet<>();
        for (BundleItem item : items) {
            if (item == null) {
                throw new IllegalArgumentException("묶음 상품 목록에 빈 값이 있습니다.");
            }
            if (!seen.add(item.orderItemId())) {
                throw new IllegalArgumentException("묶음 상품 목록에 같은 주문 상품이 두 번 있습니다. orderItemId=" + item.orderItemId());
            }
        }
        items = List.copyOf(items);
    }
}
