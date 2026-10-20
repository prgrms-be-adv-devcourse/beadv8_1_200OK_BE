package com.ok.delivery.event;

import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.delivery.service.CarrierClient;
import com.ok.delivery.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 택배사와 주고받는 흐름을 잇는다. 출고되면 택배사에 추적을 요청하고, 택배사가 배송 완료를 알리면 배송 완료를 반영한다.
 * 앱이 뜰 때는 배송 중인 shipment를 모두 다시 요청해, 재시작으로 사라진 추적 요청을 복구한다.
 */
@Component
@RequiredArgsConstructor
public class CarrierEventListener {

    private final CarrierClient carrierClient;
    private final ShipmentService shipmentService;

    // 출고가 커밋된 뒤에 요청한다. 출고가 롤백되면 이 이벤트도 전달되지 않는다
    @ApplicationModuleListener
    void on(ShipmentDispatchedEvent event) {
        carrierClient.requestTracking(event.shipmentId(), event.carrier(), event.trackingNumber());
    }

    // Mock 택배사는 대기를 메모리에서만 해서 재시작하면 알림이 사라진다. 이미 알림이 온 건은 DELIVERED라 다시 요청되지 않는다
    @EventListener(ApplicationReadyEvent.class)
    void requestTrackingForShippingShipments() {
        shipmentService.getShippingShipments().forEach(shipment ->
                carrierClient.requestTracking(shipment.shipmentId(), shipment.carrier(), shipment.trackingNumber()));
    }

    // 택배사 알림은 트랜잭션 밖(택배사 쪽 스레드)에서 오므로 커밋 후 리스너가 아닌 일반 리스너로 받는다.
    // 배송 완료 반영은 서비스가 자기 트랜잭션으로 처리한다
    @EventListener
    void on(CarrierDeliveredEvent event) {
        shipmentService.deliver(event.shipmentId());
    }
}
