package com.ok.common.event;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 판매자가 송장을 등록해 배송이 시작되었음을 알린다. 배송이 발행하고 주문이 받는다.
 * 배송 1건은 (주문, 판매자) 하나이므로, 주문은 orderId + sellerId로 판매자 묶음을 찾아 배송 중으로 바꾼다.
 */
public record ShipmentDispatchedEvent(
        Long shipmentId,         // 배송 ID
        Long orderId,            // 주문 ID
        Long sellerId,           // 판매자 회원 ID
        String carrier,          // 택배사 코드
        String trackingNumber,   // 송장번호
        LocalDateTime shippedAt  // 송장 등록(배송 시작) 시각
) implements DomainEvent {

    public ShipmentDispatchedEvent {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(carrier, "carrier는 필수입니다.");
        Objects.requireNonNull(trackingNumber, "trackingNumber는 필수입니다.");
        Objects.requireNonNull(shippedAt, "shippedAt은 필수입니다.");
    }
}