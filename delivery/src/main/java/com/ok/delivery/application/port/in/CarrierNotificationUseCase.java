package com.ok.delivery.application.port.in;

import com.ok.delivery.application.dto.CarrierNotificationCommand;

/**
 * 택배사의 배송 상태 변경 알림을 받는 유스케이스.
 */
public interface CarrierNotificationUseCase {

    /**
     * 알림을 처리한다. 알림에는 상태가 없어 택배사에 다시 조회하고, 배송 완료가 확인된 경우에만 배송 완료로 반영한다.
     * 택배사는 2XX가 아니면 알림을 다시 보내므로, 모르는 송장·이미 처리된 송장·아직 완료가 아닌 상태·조회 오류는 모두 예외 없이 끝낸다.
     * 같은 알림이 여러 번 와도 결과가 같다.
     */
    void receive(CarrierNotificationCommand command);
}
