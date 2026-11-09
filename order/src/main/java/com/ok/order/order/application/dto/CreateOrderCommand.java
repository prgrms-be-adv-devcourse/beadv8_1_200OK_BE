package com.ok.order.order.application.dto;

public record CreateOrderCommand(
        Long memberId,
        String idempotencyKey,
        String recipientName,
        String recipientPhone,
        String zipCode,
        String address,
        String addressDetail,
        String deliveryNote,
        boolean thirdPartyProvisionAgreed,
        long expectedTotalAmount
) {
}