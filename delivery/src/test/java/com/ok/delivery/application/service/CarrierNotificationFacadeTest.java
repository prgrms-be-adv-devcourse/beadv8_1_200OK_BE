package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.application.dto.CarrierNotificationCommand;
import com.ok.delivery.application.dto.ShippingShipmentResult;
import com.ok.delivery.application.port.out.CarrierClient;
import com.ok.delivery.domain.Carrier;
import com.ok.delivery.exception.DeliveryErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CarrierNotificationFacadeTest {

    private static final CarrierNotificationCommand COMMAND = new CarrierNotificationCommand("kr.cjlogistics", "1234567890");

    @Mock
    CarrierClient carrierClient;

    @Mock
    ShipmentService shipmentService;

    @InjectMocks
    CarrierNotificationFacade sut;

    @Test
    void 택배사가_배송_완료로_답하면_배송_완료를_반영한다() {
        // given
        givenShippingShipment();
        given(carrierClient.isDelivered(Carrier.CJ_LOGISTICS, "1234567890")).willReturn(true);

        // when
        sut.receive(COMMAND);

        // then
        then(shipmentService).should().deliver(1L);
    }

    @Test
    void 아직_배송_완료가_아니면_반영하지_않는다() {
        // given
        givenShippingShipment();
        given(carrierClient.isDelivered(Carrier.CJ_LOGISTICS, "1234567890")).willReturn(false);

        // when
        sut.receive(COMMAND);

        // then
        then(shipmentService).should(never()).deliver(anyLong());
    }

    @Test
    void 모르는_택배사_식별자면_아무것도_하지_않는다() {
        // given
        given(carrierClient.findCarrier("kr.cjlogistics")).willReturn(Optional.empty());

        // when
        sut.receive(COMMAND);

        // then
        then(shipmentService).shouldHaveNoInteractions();
        then(carrierClient).should(never()).isDelivered(any(), any());
    }

    @Test
    void 모르는_송장이거나_이미_배송_완료_이후면_택배사를_조회하지_않는다() {
        // given
        given(carrierClient.findCarrier("kr.cjlogistics")).willReturn(Optional.of(Carrier.CJ_LOGISTICS));
        given(shipmentService.findShippingShipment(Carrier.CJ_LOGISTICS, "1234567890")).willReturn(Optional.empty());

        // when
        sut.receive(COMMAND);

        // then
        then(carrierClient).should(never()).isDelivered(any(), any());
        then(shipmentService).should(never()).deliver(anyLong());
    }

    @Test
    void 택배사_조회가_실패해도_예외를_던지지_않고_반영하지_않는다() {
        // given
        givenShippingShipment();
        given(carrierClient.isDelivered(Carrier.CJ_LOGISTICS, "1234567890")).willThrow(new IllegalStateException("조회 실패"));

        // when & then
        assertThatCode(() -> sut.receive(COMMAND)).doesNotThrowAnyException();
        then(shipmentService).should(never()).deliver(anyLong());
    }

    @Test
    void 같은_알림이_동시에_와서_이미_배송_완료가_됐어도_예외를_던지지_않는다() {
        // given
        givenShippingShipment();
        given(carrierClient.isDelivered(Carrier.CJ_LOGISTICS, "1234567890")).willReturn(true);
        willThrow(new RestApiException(DeliveryErrorCode.INVALID_SHIPMENT_STATE)).given(shipmentService).deliver(1L);

        // when & then
        assertThatCode(() -> sut.receive(COMMAND)).doesNotThrowAnyException();
    }

    private void givenShippingShipment() {
        given(carrierClient.findCarrier("kr.cjlogistics")).willReturn(Optional.of(Carrier.CJ_LOGISTICS));
        given(shipmentService.findShippingShipment(Carrier.CJ_LOGISTICS, "1234567890"))
                .willReturn(Optional.of(new ShippingShipmentResult(1L, "CJ_LOGISTICS", "1234567890")));
    }
}
