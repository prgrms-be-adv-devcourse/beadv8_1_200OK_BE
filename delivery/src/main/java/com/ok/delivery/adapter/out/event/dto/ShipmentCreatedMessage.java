package com.ok.delivery.adapter.out.event.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 결제된 주문의 판매자 묶음 배송이 만들어졌음을 주문에 알리는 Kafka 메시지. 판매자 묶음마다 한 건이다.
 * 필드 이름·타입은 주문과의 약속이다 (#644 메시지 약속). 주문은 배송 클래스를 import하지 않고 같은 JSON 모양의 자기 DTO로 읽는다.
 *
 * @param orderItemIds 이 배송에 담긴 주문 상품 ID 목록
 * @param createdAt    배송이 만들어진 시각. 오프셋 없는 ISO-8601로 나간다
 */
public record ShipmentCreatedMessage(
        Long shipmentId,
        Long orderId,
        Long sellerId,
        List<Long> orderItemIds,
        LocalDateTime createdAt
) {

    // 빠진 값이 outbox에 남으면 계속 보내도 주문이 처리하지 못하므로 남기기 전에 막는다
    public ShipmentCreatedMessage {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(orderItemIds, "orderItemIds는 필수입니다.");
        Objects.requireNonNull(createdAt, "createdAt은 필수입니다.");
    }
}
