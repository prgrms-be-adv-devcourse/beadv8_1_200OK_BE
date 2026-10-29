package com.ok.refund.domain;

/**
 * 판매자 묶음 안의 주문 상품 한 줄과 그 주문 수량. 주문 모듈 응답에서 옮겨 담는다.
 *
 * @param orderedQuantity 주문할 때 산 개수
 */
public record BundleItem(Long orderItemId, int orderedQuantity) {
}
