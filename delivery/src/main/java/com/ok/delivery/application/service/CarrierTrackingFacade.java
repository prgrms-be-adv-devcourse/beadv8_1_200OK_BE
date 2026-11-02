package com.ok.delivery.application.service;

import com.ok.delivery.application.port.in.CarrierTrackingUseCase;
import com.ok.delivery.application.port.out.CarrierClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 택배사 추적 요청을 트랜잭션 밖에서 처리한다. 택배사 호출은 외부 호출이라 DB 트랜잭션 안에서 하지 않는다.
 * 배송 중인 shipment 조회만 {@link ShipmentService}의 읽기 트랜잭션으로 하고, 요청은 그 밖에서 건마다 보낸다.
 */
@Component
@RequiredArgsConstructor
public class CarrierTrackingFacade implements CarrierTrackingUseCase {

    private final CarrierClient carrierClient;
    private final ShipmentService shipmentService;

    @Override
    public void requestTracking(Long shipmentId, String carrier, String trackingNumber) {
        carrierClient.requestTracking(shipmentId, carrier, trackingNumber);
    }

    @Override
    public void requestTrackingForShippingShipments() {
        shipmentService.getShippingShipments().forEach(shipment ->
                carrierClient.requestTracking(shipment.shipmentId(), shipment.carrier(), shipment.trackingNumber()));
    }
}
