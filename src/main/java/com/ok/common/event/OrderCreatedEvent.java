package com.ok.common.event;

public record OrderCreatedEvent(
        Long orderId, // 주문 ID
        Long buyerId, // 구매자 회원 ID
        Long totalAmount // 결제할 총액 (상품 + 배송비 + 도서산간 운임)
) {
}
