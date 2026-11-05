package com.ok.refund.domain;

/**
 * 판매자 묶음 안의 주문 상품 한 줄. 주문 모듈이 알려 준 주문 시점 값이다.
 * 묶음 전부 취소 판단과 취소 뒤 남은 묶음 금액 계산에 쓴다.
 *
 * @param unitPrice       주문 시점 단가 (원)
 * @param orderedQuantity 산 개수
 * @throws IllegalArgumentException 값이 비었거나 범위를 벗어나면. 주문 모듈이 보낸 값이라 표준 예외로 500을 낸다(exception.md)
 */
public record BundleItem(Long orderItemId, long unitPrice, int orderedQuantity) {

    public BundleItem {
        if (orderItemId == null) {
            throw new IllegalArgumentException("묶음 상품의 주문 상품 ID는 필수입니다.");
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("묶음 상품의 단가는 0 이상이어야 합니다.");
        }
        if (orderedQuantity < 1) {
            throw new IllegalArgumentException("묶음 상품의 주문 개수는 1 이상이어야 합니다.");
        }
    }
}
