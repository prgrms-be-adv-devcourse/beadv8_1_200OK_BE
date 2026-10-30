package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.ShippingShipmentResult;
import com.ok.delivery.application.port.out.CarrierClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CarrierTrackingFacadeTest {

    @Mock
    CarrierClient carrierClient;

    @Mock
    ShipmentService shipmentService;

    @InjectMocks
    CarrierTrackingFacade sut;

    @Test
    void 송장_추적을_택배사에_요청한다() {
        // when
        sut.requestTracking(1L, "CJ_LOGISTICS", "1234567890");

        // then
        then(carrierClient).should().requestTracking(1L, "CJ_LOGISTICS", "1234567890");
    }

    @Test
    void 배송_중인_shipment를_모두_다시_추적_요청한다() {
        // given
        given(shipmentService.getShippingShipments()).willReturn(List.of(
                new ShippingShipmentResult(1L, "CJ_LOGISTICS", "1111111111"),
                new ShippingShipmentResult(2L, "HANJIN", "2222222222")));

        // when
        sut.requestTrackingForShippingShipments();

        // then
        then(carrierClient).should().requestTracking(1L, "CJ_LOGISTICS", "1111111111");
        then(carrierClient).should().requestTracking(2L, "HANJIN", "2222222222");
    }

    @Test
    void 배송_중인_shipment가_없으면_택배사에_요청하지_않는다() {
        // given
        given(shipmentService.getShippingShipments()).willReturn(List.of());

        // when
        sut.requestTrackingForShippingShipments();

        // then
        then(carrierClient).should(never()).requestTracking(anyLong(), anyString(), anyString());
    }
}
