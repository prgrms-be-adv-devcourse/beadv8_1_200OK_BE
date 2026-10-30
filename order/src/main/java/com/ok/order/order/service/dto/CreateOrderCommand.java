package com.ok.order.order.service.dto;

public record CreateOrderCommand(
        Long memberId,
        String idempotencyKey,
        String recipientName,
        String recipientPhone,
        String zipCode,
        String address,
        String addressDetail,
        boolean thirdPartyProvisionAgreed,
        long expectedTotalAmount
) {
}