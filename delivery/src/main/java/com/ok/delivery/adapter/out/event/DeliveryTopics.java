package com.ok.delivery.adapter.out.event;

/**
 * 배송이 보내는 Kafka 토픽 이름을 한곳에 둔다.
 * 토픽 이름은 받는 모듈(주문)과의 약속이라 발송 어댑터와 토픽 선언이 같은 값을 쓰도록 상수로 둔다. 이름 규칙은 {@code <보내는 모듈>.<이벤트>.v<버전>}이다(#648).
 */
public final class DeliveryTopics {

    /** 배송 → 주문: 판매자 묶음 배송이 만들어짐. 키는 orderId */
    public static final String SHIPMENT_CREATED = "delivery.shipment-created.v1";

    /** 배송 → 주문: 송장 등록으로 배송이 시작됨. 키는 orderId */
    public static final String SHIPMENT_DISPATCHED = "delivery.shipment-dispatched.v1";

    /** 배송 → 주문: 판매자 묶음 배송 완료. 키는 orderId */
    public static final String SHIPMENT_DELIVERED = "delivery.shipment-delivered.v1";

    private DeliveryTopics() {
    }
}
