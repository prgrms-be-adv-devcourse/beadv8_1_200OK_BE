package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.SellerShipmentSummaryResult;
import com.ok.delivery.application.dto.ShipmentCreateCommand;
import com.ok.delivery.application.dto.ShipmentDetailResult;
import com.ok.delivery.application.dto.ShipmentDispatchCommand;
import com.ok.delivery.application.dto.ShipmentDispatchResult;
import com.ok.delivery.application.dto.ShipmentSummaryResult;
import com.ok.delivery.application.port.in.ShipmentUseCase;
import com.ok.delivery.application.port.out.CarrierClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 배송 유스케이스를 트랜잭션 밖에서 감싸, {@link ShipmentService}의 트랜잭션이 커밋된 뒤에 할 일을 한다.
 * 출고가 커밋되면 택배사에 송장 추적을 요청한다. 택배사 호출은 외부 호출이라 트랜잭션 안에서 하지 않는다.
 * 그 밖의 유스케이스는 서비스에 그대로 맡긴다. 입력 포트를 하나로 두려고 이 Facade가 함께 구현한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentFacade implements ShipmentUseCase {

    private final ShipmentService shipmentService;
    private final CarrierClient carrierClient;

    @Override
    public void create(ShipmentCreateCommand command) {
        shipmentService.create(command);
    }

    @Override
    public ShipmentDispatchResult dispatch(ShipmentDispatchCommand command) {
        ShipmentDispatchResult result = shipmentService.dispatch(command);
        requestTracking(result);
        return result;
    }

    @Override
    public void deliver(Long shipmentId) {
        shipmentService.deliver(shipmentId);
    }

    @Override
    public void confirmPurchase(Long orderItemId) {
        shipmentService.confirmPurchase(orderItemId);
    }

    @Override
    public Page<ShipmentSummaryResult> getBuyerShipments(Long buyerId, int page) {
        return shipmentService.getBuyerShipments(buyerId, page);
    }

    @Override
    public Page<SellerShipmentSummaryResult> getSellerShipments(Long sellerId, int page) {
        return shipmentService.getSellerShipments(sellerId, page);
    }

    @Override
    public ShipmentDetailResult getBuyerShipment(Long buyerId, Long shipmentId) {
        return shipmentService.getBuyerShipment(buyerId, shipmentId);
    }

    @Override
    public ShipmentDetailResult getSellerShipment(Long sellerId, Long shipmentId) {
        return shipmentService.getSellerShipment(sellerId, shipmentId);
    }

    // 출고는 이미 커밋됐으므로 요청이 실패해도 출고 응답을 실패로 바꾸지 않는다. 놓친 요청은 앱이 뜰 때 배송 중인 shipment를 다시 요청해 채운다(CarrierEventListener)
    private void requestTracking(ShipmentDispatchResult result) {
        try {
            carrierClient.requestTracking(result.shipmentId(), result.carrier(), result.trackingNumber());
        } catch (RuntimeException e) {
            log.warn("택배사 추적 요청 실패, 재시작 때 다시 요청함. shipmentId={}", result.shipmentId(), e);
        }
    }
}
