package com.ok.delivery.adapter.out.event;

/**
 * 배송이 보내는 Kafka 토픽 이름을 한곳에 둔다.
 * 토픽 이름은 받는 모듈(주문)과의 약속이라 발송 어댑터와 토픽 선언 설정이 같은 값을 쓰도록 상수로 둔다.
 * 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다(#339 합의).
 * 두 토픽 모두 키는 orderId다. 같은 주문의 배송 시작과 완료가 같은 파티션으로 가서 순서가 지켜진다 (#644 메시지 약속).
 */
public final class DeliveryTopics {

    /** 배송 → 주문: 송장 등록으로 배송이 시작됨. 값은 {@link com.ok.delivery.adapter.out.event.dto.ShipmentDispatchedMessage} */
    public static final String SHIPMENT_DISPATCHED = "delivery.shipment-dispatched";

    /** 배송 → 주문: 배송이 완료됨. 값은 {@link com.ok.delivery.adapter.out.event.dto.ShipmentDeliveredMessage} */
    public static final String SHIPMENT_DELIVERED = "delivery.shipment-delivered";

    private DeliveryTopics() {
    }
}
