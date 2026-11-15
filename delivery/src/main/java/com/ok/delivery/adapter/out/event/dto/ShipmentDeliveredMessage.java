package com.ok.delivery.adapter.out.event.dto;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 배송 완료를 주문에 알리는 Kafka 메시지. 필드 이름·타입은 주문과의 약속이다 (#644 메시지 약속).
 * 주문은 deliveredAt을 자동 구매확정 기준으로 쓴다.
 *
 * @param deliveredAt 배송 완료 시각. 오프셋 없는 ISO-8601로 나간다
 */
public record ShipmentDeliveredMessage(
        Long shipmentId,
        Long orderId,
        Long sellerId,
        LocalDateTime deliveredAt
) {

    // 서비스가 넘긴 값이지만 빠지면 주문이 판매자 묶음을 찾지 못한다. outbox에 남기기 전에 막는다
    public ShipmentDeliveredMessage {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(deliveredAt, "deliveredAt은 필수입니다.");
    }
}
