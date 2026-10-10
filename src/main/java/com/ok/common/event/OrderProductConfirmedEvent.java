package com.ok.common.event;

public record OrderProductConfirmedEvent(
        Long orderItemId,        // 주문항목 ID
        Long sellerId,           // 판매자
        Long totalItemPrice,     // 상품 총액
        Long shippingPrice       // 이 주문상품의 배송비 (상품 단위 부과, 전액 판매자 귀속. 무료배송이면 0)
) {

}