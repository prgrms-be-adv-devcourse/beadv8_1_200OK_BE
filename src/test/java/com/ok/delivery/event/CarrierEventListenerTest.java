package com.ok.delivery.event;

import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.delivery.service.CarrierClient;
import com.ok.delivery.service.ShipmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class CarrierEventListenerTest {

    @Mock
    CarrierClient carrierClient;

    @Mock
    ShipmentService shipmentService;

    @InjectMocks
    CarrierEventListener sut;

    @Test
    void 출고_이벤트를_받으면_택배사에_송장_추적을_요청한다() {
        // given
        ShipmentDispatchedEvent event = new ShipmentDispatchedEvent(1L, 2L, 10L, "CJ_LOGISTICS", "1234567890",
                LocalDateTime.of(2026, 10, 21, 10, 0));

        // when
        sut.on(event);

        // then
        then(carrierClient).should().requestTracking(1L, "CJ_LOGISTICS", "1234567890");
    }

    @Test
    void 택배사의_배송_완료_알림을_받으면_배송_완료를_반영한다() {
        // when
        sut.on(new CarrierDeliveredEvent(1L));

        // then
        then(shipmentService).should().deliver(1L);
    }
}
