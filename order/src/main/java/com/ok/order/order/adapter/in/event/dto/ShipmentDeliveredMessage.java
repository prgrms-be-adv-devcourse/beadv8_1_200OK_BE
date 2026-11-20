package com.ok.order.order.adapter.in.event.dto;

import java.time.LocalDateTime;

/**
 * 배송이 {@code delivery.shipment-delivered.v1}로 보내는 배송 완료를 주문이 읽는 모양. 필드 이름·타입은 배송과의 약속이다(#603, 배송 #644).
 * 배송 클래스를 import하지 않고 같은 JSON 모양의 주문 DTO로 읽는다.
 *
 * @param deliveredAt 배송 완료 시각. 오프셋 없는 ISO-8601(Asia/Seoul). 자동 구매확정 기간의 기준이다
 */
public record ShipmentDeliveredMessage(
        Long shipmentId,
        Long orderId,
        Long sellerId,
        LocalDateTime deliveredAt
) {
}
