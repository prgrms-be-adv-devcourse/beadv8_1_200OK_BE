package com.ok.order.order.adapter.in.event;

/**
 * 주문이 배송에게서 Kafka로 받는 토픽 이름과 주문 리스너의 컨슈머 그룹. 토픽 이름은 배송과의 약속이라 배송과 같은 값이어야 받는다(#603, 배송 #644).
 * 배송 클래스를 import하지 않고 같은 문자열을 여기 따로 둔다(모듈 경계).
 */
final class DeliveryTopics {

    // 배송 → 주문: 판매자 묶음 출고(배송 시작). 키는 orderId
    static final String SHIPMENT_DISPATCHED = "delivery.shipment-dispatched.v1";

    // 배송 → 주문: 판매자 묶음 배송 완료. 키는 orderId
    static final String SHIPMENT_DELIVERED = "delivery.shipment-delivered.v1";

    // 그룹 이름은 <모듈>.<용도>(#648). 배송 시작과 완료는 한 주문의 배송 상태라 같은 용도로 묶는다
    static final String SHIPPING_STATUS_GROUP = "order.shipping-status";

    private DeliveryTopics() {
    }
}
