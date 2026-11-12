package com.ok.delivery.adapter.in.event;

import java.util.Objects;

/**
 * 택배사 조회에서 배송 완료가 아닌 새 상태를 확인했음을 나타낸다. 택배사 연동 구현체가 발행한다.
 * 배송 모듈 안에서만 쓰므로 모듈 간 이벤트({@code common.event})에 두지 않는다.
 *
 * @param time 택배사가 알려 준 상태 시각. 비어 올 수 있다.
 */
public record CarrierStatusReportedEvent(Long shipmentId, String statusCode, String time) {

    public CarrierStatusReportedEvent {
        Objects.requireNonNull(shipmentId, "shipmentId는 필수입니다.");
        Objects.requireNonNull(statusCode, "statusCode는 필수입니다.");
    }

    // 택배사 이벤트에는 ID가 없고 상태 코드가 거칠어서 시각을 함께 구분자로 쓴다(docs/design/order-flow-tracking.md §6.2)
    // 시각이 비면 `-`로 두어 같은 상태 코드의 서로 다른 사건이 합쳐진다. 설계의 해시 대체는 설명·장소 필드가 확인되지 않아 쓰지 않는다
    public String eventKey() {
        return statusCode + ":" + (time == null ? "-" : time);
    }
}
