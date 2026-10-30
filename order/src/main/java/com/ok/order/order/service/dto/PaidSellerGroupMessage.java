package com.ok.order.order.service.dto;

import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderSellerGroup;

import java.util.ArrayList;
import java.util.List;

// 결제된 주문의 판매자 묶음 하나 (배송 1건이 된다)
public record PaidSellerGroupMessage(
        Long sellerId,
        Long baseShippingFee,       // 판매자 설정의 기본 배송비, 무료배송이어도 설정 값
        Long freeShippingThreshold, // null이면 무료배송 없음
        Long regionSurcharge,
        List<PaidItemMessage> items
) {
    public static PaidSellerGroupMessage from(OrderSellerGroup group) {
        List<PaidItemMessage> items = new ArrayList<>();
        for (OrderItem item : group.getItems()) {
            items.add(PaidItemMessage.from(item));
        }
        return new PaidSellerGroupMessage(
                group.getSellerId(),
                group.getBaseShippingFee(),
                group.getFreeShippingThreshold(),
                group.getRemoteAreaFee(),
                items
        );
    }
}