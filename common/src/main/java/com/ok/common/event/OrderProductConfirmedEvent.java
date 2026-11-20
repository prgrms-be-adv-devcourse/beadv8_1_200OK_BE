package com.ok.common.event;

public record OrderProductConfirmedEvent(
        Long orderId,               // 주문 ID
        Long orderItemId,           // 주문항목 ID
        Long sellerId,              // 판매자
        Long totalItemPrice,        // 상품 총액
        Long sellerShippingPrice    // 판매자 묶음 배송비 (같은 묶음의 상품마다 같은 값이 옴, 전액 판매자 귀속. 무료배송이면 0)
) implements DomainEvent {

}
