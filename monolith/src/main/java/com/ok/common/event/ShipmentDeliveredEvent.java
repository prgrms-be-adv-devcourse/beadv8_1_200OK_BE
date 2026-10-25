package com.ok.common.event;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 배송이 완료되었음을 알린다. 배송이 발행하고 주문이 받는다.
 * 주문은 deliveredAt을 그대로 저장하고, 이 시각이 자동 구매확정(배송 완료 3일 후)의 기준이 된다.
 */
public record ShipmentDeliveredEvent(
        Long shipmentId,            // 배송 ID
        Long orderId,               // 주문 ID
        Long sellerId,              // 판매자 회원 ID
        LocalDateTime deliveredAt   // 배송 완료 시각
) implements DomainEvent {

    public ShipmentDeliveredEvent {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(deliveredAt, "deliveredAt은 필수입니다.");
    }
}