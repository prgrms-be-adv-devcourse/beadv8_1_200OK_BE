package com.ok.common.event;

public record OrderProductConfirmedEvent(
        Long orderItemId,        // 주문항목 ID
        Long sellerId,           // 판매자
        Long totalItemPrice,     // 상품 총액
        Long shippingPrice       // 배송비
) {

}