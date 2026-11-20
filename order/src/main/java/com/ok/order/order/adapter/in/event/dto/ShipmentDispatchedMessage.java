package com.ok.order.order.adapter.in.event.dto;

import java.time.LocalDateTime;

/**
 * 배송이 {@code delivery.shipment-dispatched.v1}로 보내는 출고(배송 시작)를 주문이 읽는 모양. 필드 이름·타입은 배송과의 약속이다(#603, 배송 #644).
 * 배송 클래스를 import하지 않고 같은 JSON 모양의 주문 DTO로 읽는다. 주문은 orderId·sellerId만 쓰고 나머지는 약속을 보여 주려고 함께 둔다.
 *
 * @param shippedAt 출고 시각. 오프셋 없는 ISO-8601(Asia/Seoul)
 */
public record ShipmentDispatchedMessage(
        Long shipmentId,
        Long orderId,
        Long sellerId,
        String carrier,
        String trackingNumber,
        LocalDateTime shippedAt
) {
}
