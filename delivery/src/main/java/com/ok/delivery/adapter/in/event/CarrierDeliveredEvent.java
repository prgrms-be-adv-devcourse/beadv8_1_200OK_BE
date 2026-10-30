package com.ok.delivery.adapter.in.event;

import java.util.Objects;

/**
 * 택배사가 배송 완료를 알렸음을 나타낸다. 택배사 연동 구현체가 발행하고 배송 모듈이 받는다.
 * 배송 모듈 안에서만 쓰므로 모듈 간 이벤트({@code common.event})에 두지 않는다.
 */
public record CarrierDeliveredEvent(Long shipmentId) {

    public CarrierDeliveredEvent {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
    }
}
