package com.ok.delivery.application.dto;

import java.util.Objects;

/**
 * 출고가 커밋되어 택배사에 송장 추적을 요청해야 함을 나타낸다. 발행 구현체가 출고 트랜잭션 안에서 발행하고 택배사 리스너가 커밋 뒤에 받는다.
 * 같은 서버 안의 일이라 Kafka를 거치지 않는 배송 안 이벤트다 (#644 결정 ③). 모듈 간 계약({@code common.event})의 배송 시작 이벤트와 이름을 나눈다.
 * 발행하는 쪽(adapter.out)과 받는 쪽(adapter.in)이 함께 쓸 수 있는 곳이 application.dto라 여기 둔다 ({@code ArchitectureTest}).
 */
public record CarrierTrackingRequestedEvent(Long shipmentId, String carrier, String trackingNumber) {

    public CarrierTrackingRequestedEvent {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
        Objects.requireNonNull(carrier, "carrier는 필수입니다.");
        Objects.requireNonNull(trackingNumber, "trackingNumber는 필수입니다.");
    }
}
