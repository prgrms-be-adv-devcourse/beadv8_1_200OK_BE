package com.ok.order.order.service.dto;

import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderSellerGroup;

import java.util.ArrayList;
import java.util.List;

// 결제된 주문의 판매자 묶음 하나 (배송 1건이 된다)
public record PaidSellerGroupMessage(
        Long sellerId,
        Long baseShippingFee,
        Long freeShippingThreshold,
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
                0L,   // TODO 주문 배송비 계산이 붙으면 판매자 설정의 기본 배송비로 변경 (지금은 주문 배송비도 0원)
                0L,   // TODO 위와 같음 (무료배송 기준 금액)
                group.getRemoteAreaFee(),
                items
        );
    }
}