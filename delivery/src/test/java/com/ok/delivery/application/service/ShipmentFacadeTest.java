package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.application.dto.ShipmentDispatchCommand;
import com.ok.delivery.application.dto.ShipmentDispatchResult;
import com.ok.delivery.application.port.out.CarrierClient;
import com.ok.delivery.exception.DeliveryErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class ShipmentFacadeTest {

    private static final ShipmentDispatchCommand DISPATCH_COMMAND = new ShipmentDispatchCommand(1L, 10L, "CJ_LOGISTICS", "1234567890");
    private static final ShipmentDispatchResult DISPATCHED = new ShipmentDispatchResult(
            1L, "SHIPPING", "CJ_LOGISTICS", "1234567890", LocalDateTime.of(2026, 11, 20, 10, 0), 100L);

    @Mock
    ShipmentService shipmentService;

    @Mock
    CarrierClient carrierClient;

    @InjectMocks
    ShipmentFacade sut;

    @Test
    void 출고하면_출고한_송장으로_택배사에_추적을_요청하고_출고_결과를_돌려준다() {
        // given
        given(shipmentService.dispatch(DISPATCH_COMMAND)).willReturn(DISPATCHED);

        // when
        ShipmentDispatchResult result = sut.dispatch(DISPATCH_COMMAND);

        // then
        assertThat(result).isEqualTo(DISPATCHED);
        then(carrierClient).should().requestTracking(1L, "CJ_LOGISTICS", "1234567890");
    }

    @Test
    void 출고가_실패하면_택배사에_추적을_요청하지_않는다() {
        // given
        given(shipmentService.dispatch(DISPATCH_COMMAND)).willThrow(new RestApiException(DeliveryErrorCode.TRACKING_NUMBER_DUPLICATED));

        // when & then
        assertThatThrownBy(() -> sut.dispatch(DISPATCH_COMMAND))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.TRACKING_NUMBER_DUPLICATED));
        then(carrierClient).shouldHaveNoInteractions();
    }

    @Test
    void 추적_요청이_실패해도_커밋된_출고_결과를_돌려준다() {
        // given
        given(shipmentService.dispatch(DISPATCH_COMMAND)).willReturn(DISPATCHED);
        willThrow(new IllegalStateException("추적 스레드가 멈춤"))
                .given(carrierClient).requestTracking(1L, "CJ_LOGISTICS", "1234567890");

        // when
        ShipmentDispatchResult result = sut.dispatch(DISPATCH_COMMAND);

        // then
        assertThat(result).isEqualTo(DISPATCHED);
    }
}
