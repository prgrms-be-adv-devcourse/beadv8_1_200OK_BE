package com.ok.order.order.service.dto;

import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;

import java.time.LocalDateTime;

// 리뷰를 쓸 수 있는 구매 건 하나 (주문 상품 단위)
public record ReviewCandidateResult(
        Long orderId,
        Long orderItemId,
        Long productId,
        Long productOptionId,
        LocalDateTime orderedAt
) {
    public static ReviewCandidateResult from(OrderItem item) {
        Order order = item.getSellerGroup().getOrder();
        return new ReviewCandidateResult(
                order.getId(),
                item.getId(),
                item.getProductId(),
                item.getProductOptionId(),
                order.getCreatedAt()
        );
    }
}