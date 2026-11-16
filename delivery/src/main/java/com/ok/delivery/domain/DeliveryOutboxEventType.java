package com.ok.delivery.domain;

/**
 * 배송 outbox로 보내는 메시지 종류. 종류마다 토픽과 메시지 모양이 정해진다 (#644 메시지 약속).
 */
public enum DeliveryOutboxEventType {

    /** 결제된 주문의 판매자별 배송이 만들어짐 (토픽 delivery.shipment-created.v1) */
    SHIPMENT_CREATED,

    /** 송장 등록으로 배송이 시작됨 (토픽 delivery.shipment-dispatched.v1) */
    SHIPMENT_DISPATCHED,

    /** 배송 완료 (토픽 delivery.shipment-delivered.v1) */
    SHIPMENT_DELIVERED

}
