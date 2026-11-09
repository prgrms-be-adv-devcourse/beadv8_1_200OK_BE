package com.ok.delivery.adapter.in.event;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 출고 후 조회 상한을 넘겨 택배사 조회를 멈췄음을 나타낸다. 택배사 연동 구현체가 발행한다.
 * 배송 모듈 안에서만 쓰므로 모듈 간 이벤트({@code common.event})에 두지 않는다.
 * 송장번호는 개인 식별 정보에 가까워 담지 않는다. shipmentId로 찾는다.
 */
public record CarrierTrackingGaveUpEvent(Long shipmentId, LocalDateTime shippedAt, Duration maxTrackingPeriod) {

    public CarrierTrackingGaveUpEvent {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
        Objects.requireNonNull(shippedAt, "shippedAt은 필수입니다.");
        Objects.requireNonNull(maxTrackingPeriod, "maxTrackingPeriod는 필수입니다.");
    }
}
