package com.ok.delivery.service.dto;

/**
 * 판매자 한 명의 상품 금액 합계다. 무료배송 기준을 판매자별 합계로 판단하므로 판매자 단위로 받는다.
 *
 * @param itemAmount 이 판매자 상품의 금액 합계(원)
 */
public record SellerItemAmount(Long sellerId, long itemAmount) {

    /**
     * 생성할 때 값을 검증한다.
     * 요청 형식은 요청 DTO가 먼저 검증하므로, 여기까지 잘못된 값이 오면 호출한 코드의 버그로 본다.
     *
     * @throws IllegalArgumentException 판매자 ID가 없거나 상품 금액이 음수면
     */
    public SellerItemAmount {
        if (sellerId == null) {
            throw new IllegalArgumentException("sellerId는 필수입니다.");
        }
        if (itemAmount < 0) {
            throw new IllegalArgumentException("상품 금액은 0 이상이어야 합니다.");
        }
    }
}
