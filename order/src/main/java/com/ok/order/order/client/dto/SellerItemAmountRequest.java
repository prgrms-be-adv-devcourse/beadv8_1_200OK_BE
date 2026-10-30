package com.ok.order.order.client.dto;

// 무료배송 기준을 판매자별 상품 금액 합계로 판단하므로 판매자마다 합계를 보낸다
public record SellerItemAmountRequest(Long sellerId, long itemAmount) {
}
