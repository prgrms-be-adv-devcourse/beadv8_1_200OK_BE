package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.application.dto.CarrierNotificationCommand;
import com.ok.delivery.application.dto.ShippingShipmentResult;
import com.ok.delivery.application.port.in.CarrierNotificationUseCase;
import com.ok.delivery.application.port.out.CarrierClient;
import com.ok.delivery.domain.Carrier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 택배사 알림을 트랜잭션 밖에서 처리한다. 택배사 조회는 외부 호출이라 DB 트랜잭션 안에서 하지 않고,
 * shipment 조회는 {@link ShipmentService}가, 배송 완료 반영과 그 메시지 발송은 {@link ShipmentFacade}가 맡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CarrierNotificationFacade implements CarrierNotificationUseCase {

    private final CarrierClient carrierClient;
    private final ShipmentService shipmentService;
    private final ShipmentFacade shipmentFacade;

    @Override
    public void receive(CarrierNotificationCommand command) {
        Optional<Carrier> carrier = carrierClient.findCarrier(command.carrierId());
        Optional<ShippingShipmentResult> shipment = carrier.flatMap(
                found -> shipmentService.findShippingShipment(found, command.trackingNumber()));
        if (shipment.isEmpty()) {
            // 모르는 택배사·송장이거나 이미 배송 완료 이후다. 재전송해도 달라지지 않으므로 무시한다
            log.info("배송 중인 shipment가 없는 택배사 알림, 무시. carrierId={}", command.carrierId());
            return;
        }
        if (!isDeliveredAtCarrier(carrier.get(), command.trackingNumber())) {
            return;
        }
        deliver(shipment.get().shipmentId());
    }

    // 조회가 일시적으로 실패해도 알림은 받은 것으로 끝낸다. 배송 완료 확인은 조회 스케줄이 이어서 맡는다
    private boolean isDeliveredAtCarrier(Carrier carrier, String trackingNumber) {
        try {
            return carrierClient.isDelivered(carrier, trackingNumber);
        } catch (RuntimeException e) {
            log.warn("택배사 알림 후 배송 상태 조회 실패. 조회 스케줄이 대신 확인합니다. carrier={}", carrier, e);
            return false;
        }
    }

    // 같은 알림이 동시에 오면 뒤따르는 쪽은 행 잠금을 기다렸다가 이미 배송 완료인 상태를 만난다. 결과가 같으므로 오류로 보지 않는다
    private void deliver(Long shipmentId) {
        try {
            shipmentFacade.deliver(shipmentId);
            log.info("택배사 알림으로 배송 완료를 반영했습니다. shipmentId={}", shipmentId);
        } catch (RestApiException e) {
            log.info("이미 반영된 배송 완료 알림, 무시. shipmentId={}", shipmentId);
        }
    }
}
