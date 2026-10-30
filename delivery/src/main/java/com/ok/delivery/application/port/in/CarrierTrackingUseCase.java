package com.ok.delivery.application.port.in;

/**
 * 택배사에 송장 추적을 요청하는 유스케이스.
 */
public interface CarrierTrackingUseCase {

    /**
     * 출고된 송장의 추적을 택배사에 요청한다.
     *
     * @param carrier 택배사 코드
     */
    void requestTracking(Long shipmentId, String carrier, String trackingNumber);

    /**
     * 배송 중인 shipment를 모두 택배사에 다시 요청한다. 재시작으로 사라진 추적 요청을 복구할 때 쓴다.
     */
    void requestTrackingForShippingShipments();
}
