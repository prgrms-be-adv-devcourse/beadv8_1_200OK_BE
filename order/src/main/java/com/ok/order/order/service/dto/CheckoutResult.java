package com.ok.order.order.service.dto;

import com.ok.order.order.domain.Order;

import java.util.List;

// 배송비는 프론트가 배송 모듈 견적 API로 받으므로 주문서에는 상품 금액만 담는다
public record CheckoutResult(
        List<CheckoutSellerGroupResult> sellerGroups,
        long totalProductAmount
) {
    public static CheckoutResult from(Order order) {
        return new CheckoutResult(
                order.getSellerGroups().stream().map(CheckoutSellerGroupResult::from).toList(),
                order.getTotalProductAmount()
        );
    }
}