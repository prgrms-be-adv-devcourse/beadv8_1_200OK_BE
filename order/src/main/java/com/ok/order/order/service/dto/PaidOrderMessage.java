package com.ok.order.order.service.dto;

import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderSellerGroup;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 결제 완료 이벤트에 담을 주문 정보. Service가 엔티티에서 값만 꺼내 event 패키지로 넘길 때 쓴다
public record PaidOrderMessage(
        Long orderId,
        Long buyerId,
        LocalDateTime paidAt,
        String recipientName,
        String recipientPhone,
        String zipCode,
        String address,
        String addressDetail,
        String deliveryNote,
        List<PaidSellerGroupMessage> sellerGroups
) {
    public static PaidOrderMessage from(Order order, LocalDateTime paidAt) {
        List<PaidSellerGroupMessage> groups = new ArrayList<>();
        for (OrderSellerGroup group : order.getSellerGroups()) {
            groups.add(PaidSellerGroupMessage.from(group));
        }
        return new PaidOrderMessage(
                order.getId(),
                order.getBuyerId(),
                paidAt,
                order.getRecipientName(),
                order.getRecipientPhone(),
                order.getZipCode(),
                order.getAddress(),
                order.getAddressDetail(),
                order.getDeliveryNote(),
                groups
        );
    }
}