package com.ok.delivery.adapter.out.event.dto;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 배송 시작을 주문에 알리는 Kafka 메시지. 필드 이름·타입은 주문과의 약속이다 (#644 메시지 약속).
 * 주문은 배송 클래스를 import하지 않고 같은 JSON 모양의 자기 DTO로 읽는다.
 *
 * @param carrier   택배사 코드
 * @param shippedAt 송장 등록(배송 시작) 시각. 오프셋 없는 ISO-8601로 나간다
 */
public record ShipmentDispatchedMessage(
        Long shipmentId,
        Long orderId,
        Long sellerId,
        String carrier,
        String trackingNumber,
        LocalDateTime shippedAt
) {

    // 빠진 값이 outbox에 남으면 계속 보내도 주문이 처리하지 못하므로 남기기 전에 막는다
    public ShipmentDispatchedMessage {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(carrier, "carrier는 필수입니다.");
        Objects.requireNonNull(trackingNumber, "trackingNumber는 필수입니다.");
        Objects.requireNonNull(shippedAt, "shippedAt은 필수입니다.");
    }
}
