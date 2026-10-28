package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.CheckoutResult;

import java.util.List;

public record CheckoutResponse(
        List<CheckoutSellerGroupResponse> sellerGroups, // 판매자별 묶음
        long totalProductAmount, // 상품 금액 합계
        long totalShippingFee, // 배송비 합계
        long totalAmount // 합계 (도서산간 운임 제외, 주소 입력 후 프론트가 더함)
) {
    public static CheckoutResponse from(CheckoutResult result) {
        return new CheckoutResponse(
                result.sellerGroups().stream().map(CheckoutSellerGroupResponse::from).toList(),
                result.totalProductAmount(),
                result.totalShippingFee(),
                result.totalAmount()
        );
    }
}