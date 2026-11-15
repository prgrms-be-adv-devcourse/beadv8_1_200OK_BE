package com.ok.delivery.adapter.out.event.dto;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 배송 시작을 주문에 알리는 Kafka 메시지. 필드 이름·타입은 주문과의 약속이다 (#644 메시지 약속).
 * 모듈 간 계약 타입({@code common.event})을 쓰지 않고 배송이 자기 모양을 소유한다. 주문은 같은 JSON 모양의 자기 DTO로 읽는다.
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

    // 서비스가 넘긴 값이지만 빠지면 주문이 판매자 묶음을 찾지 못한다. outbox에 남기기 전에 막는다
    public ShipmentDispatchedMessage {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(carrier, "carrier는 필수입니다.");
        Objects.requireNonNull(trackingNumber, "trackingNumber는 필수입니다.");
        Objects.requireNonNull(shippedAt, "shippedAt은 필수입니다.");
    }
}
