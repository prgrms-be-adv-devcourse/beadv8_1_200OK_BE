package com.ok.delivery.application.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 배송비 견적 조건이다.
 *
 * @param postalCode 배송지 우편번호(숫자 5자리)
 * @param sellers    판매자별 상품 금액. 응답도 이 순서를 따른다
 */
public record ShippingFeeQuoteQuery(String postalCode, List<SellerItemAmount> sellers) {

    /**
     * 생성할 때 값을 검증한다.
     * 같은 판매자가 두 번 들어오면 송장이 둘로 계산되어 배송비가 두 번 붙으므로 막는다.
     *
     * @throws IllegalArgumentException 우편번호가 없거나, 판매자 목록이 비었거나 null 항목·중복 판매자가 있으면
     */
    public ShippingFeeQuoteQuery {
        if (postalCode == null) {
            throw new IllegalArgumentException("우편번호는 필수입니다.");
        }
        if (sellers == null || sellers.isEmpty() || sellers.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("판매자 목록은 1개 이상이어야 하고 null 항목을 포함할 수 없습니다.");
        }
        Set<Long> sellerIds = new HashSet<>();
        for (SellerItemAmount seller : sellers) {
            if (!sellerIds.add(seller.sellerId())) {
                throw new IllegalArgumentException("판매자가 중복되었습니다. sellerId=" + seller.sellerId());
            }
        }
        sellers = List.copyOf(sellers);
    }

    public List<Long> sellerIds() {
        return sellers.stream().map(SellerItemAmount::sellerId).toList();
    }
}
