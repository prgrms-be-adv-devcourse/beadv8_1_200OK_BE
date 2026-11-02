package com.ok.refund.domain;

/**
 * 판매자 묶음 안의 주문 상품 한 줄과 그 단가·주문 수량. 주문 모듈 응답에서 옮겨 담는다.
 *
 * @param unitPrice       주문 시점 단가 (원). 취소 뒤 남은 묶음 금액을 구할 때 쓴다
 * @param orderedQuantity 주문할 때 산 개수
 */
public record BundleItem(Long orderItemId, long unitPrice, int orderedQuantity) {
}
