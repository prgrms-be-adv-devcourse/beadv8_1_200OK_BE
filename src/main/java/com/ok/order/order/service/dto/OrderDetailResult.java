package com.ok.order.order.service.dto;

import com.ok.order.order.domain.Order;

import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResult(
        Long orderId,
        String status, // enum 대신 문자열 (controller는 domain을 쓰지 않음)
        LocalDateTime orderedAt,
        String recipientName,
        String recipientPhone,
        String zipCode,
        String address,
        String addressDetail,
        List<OrderDetailSellerGroupResult> sellerGroups,
        long totalProductAmount,
        long totalShippingFee,
        long totalRemoteAreaFee,
        long totalAmount
) {
    public static OrderDetailResult from(Order order) {
        return new OrderDetailResult(
                order.getId(),
                order.getStatus().name(),
                order.getCreatedAt(),
                order.getRecipientName(),
                order.getRecipientPhone(),
                order.getZipCode(),
                order.getAddress(),
                order.getAddressDetail(),
                order.getSellerGroups().stream().map(OrderDetailSellerGroupResult::from).toList(),
                order.getTotalProductAmount(),
                order.getTotalShippingFee(),
                order.getTotalRemoteAreaFee(),
                order.getTotalAmount()
        );
    }
}