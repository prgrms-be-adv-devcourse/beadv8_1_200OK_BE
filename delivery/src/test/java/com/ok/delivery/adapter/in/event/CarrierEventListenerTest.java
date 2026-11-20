package com.ok.delivery.adapter.in.event;

import com.ok.delivery.application.port.in.CarrierTrackingUseCase;
import com.ok.delivery.application.port.in.ShipmentUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class CarrierEventListenerTest {

    @Mock
    CarrierTrackingUseCase carrierTrackingUseCase;

    @Mock
    ShipmentUseCase shipmentUseCase;

    @InjectMocks
    CarrierEventListener sut;

    @Test
    void 택배사의_배송_완료_알림을_받으면_배송_완료를_반영한다() {
        // when
        sut.on(new CarrierDeliveredEvent(1L));

        // then
        then(shipmentUseCase).should().deliver(1L);
    }

    @Test
    void 앱이_뜨면_배송_중인_shipment의_추적_재요청을_맡긴다() {
        // when
        sut.requestTrackingForShippingShipments();

        // then
        then(carrierTrackingUseCase).should().requestTrackingForShippingShipments();
    }
}
