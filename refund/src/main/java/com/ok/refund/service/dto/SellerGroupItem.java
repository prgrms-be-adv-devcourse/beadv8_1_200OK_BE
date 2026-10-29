package com.ok.refund.service.dto;

/**
 * 판매자 묶음 안의 주문 상품 한 줄과 산 개수.
 */
public record SellerGroupItem(Long orderItemId, int quantity) {
}
