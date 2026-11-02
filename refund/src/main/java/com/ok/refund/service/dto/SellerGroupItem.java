package com.ok.refund.service.dto;

/**
 * 판매자 묶음 안의 주문 상품 한 줄과 산 개수.
 *
 * @param unitPrice 주문 시점 단가 (원). 취소 뒤 남은 묶음 금액으로 무료배송 기준이 깨지는지 볼 때 쓴다
 */
public record SellerGroupItem(Long orderItemId, long unitPrice, int quantity) {
}
