package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.CheckoutResult;

import java.util.List;

// 배송비·도서산간 운임은 프론트가 배송 견적 API로 받아 더한다
public record CheckoutResponse(
        List<CheckoutSellerGroupResponse> sellerGroups, // 판매자별 묶음
        long totalProductAmount // 상품 금액 합계
) {
    public static CheckoutResponse from(CheckoutResult result) {
        return new CheckoutResponse(
                result.sellerGroups().stream().map(CheckoutSellerGroupResponse::from).toList(),
                result.totalProductAmount()
        );
    }
}