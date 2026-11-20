package com.ok.common.event;

import java.util.Objects;

public record OrderCreatedEvent(
        Long orderId, // 주문 ID
        Long buyerId, // 구매자 회원 ID
        Long totalAmount // 결제할 총액 (상품 + 배송비 + 도서산간 운임)
) implements DomainEvent{

    public OrderCreatedEvent {
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(buyerId, "buyerId는 필수입니다.");
        Objects.requireNonNull(totalAmount, "totalAmount는 필수입니다.");
    }
}
