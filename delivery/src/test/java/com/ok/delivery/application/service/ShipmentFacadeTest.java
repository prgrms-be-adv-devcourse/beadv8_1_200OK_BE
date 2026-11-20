package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.application.dto.ShipmentCreateCommand;
import com.ok.delivery.application.dto.ShipmentDispatchCommand;
import com.ok.delivery.application.dto.ShipmentDispatchResult;
import com.ok.delivery.application.port.out.CarrierClient;
import com.ok.delivery.config.DeliveryAsyncConfig;
import com.ok.delivery.exception.DeliveryErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

// 커밋 직후 실제 Kafka로 나가는지, 대기열이 가득 찼을 때 발송 작업이 보내는지는 DeliveryOutboxKafkaIntegrationTest가 본다
@ExtendWith(MockitoExtension.class)
class ShipmentFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
    private static final ShipmentDispatchCommand DISPATCH_COMMAND = new ShipmentDispatchCommand(1L, 10L, "CJ_LOGISTICS", "1234567890");
    private static final ShipmentDispatchResult DISPATCHED = new ShipmentDispatchResult(
            1L, "SHIPPING", "CJ_LOGISTICS", "1234567890", LocalDateTime.of(2026, 11, 20, 10, 0), 100L);

    @Mock
    ShipmentService shipmentService;

    @Mock
    DeliveryOutboxRelayFacade deliveryOutboxRelayFacade;

    @Mock
    CarrierClient carrierClient;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ShipmentFacade sut;

    @BeforeEach
    void setUp() {
        sut = new ShipmentFacade(shipmentService, deliveryOutboxRelayFacade, carrierClient,
                Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);
    }

    @Test
    void 배송을_만들면_판매자마다_남긴_줄을_서비스가_끝난_시각과_함께_바로_보낸다() {
        // given
        ShipmentCreateCommand command = mock(ShipmentCreateCommand.class);
        given(shipmentService.create(command)).willReturn(List.of(70L, 71L));

        // when
        sut.create(command);

        // then
        then(deliveryOutboxRelayFacade).should().send(70L, NOW);
        then(deliveryOutboxRelayFacade).should().send(71L, NOW);
    }

    @Test
    void 이미_처리된_결제_완료라_남긴_줄이_없으면_보내지_않는다() {
        // given
        ShipmentCreateCommand command = mock(ShipmentCreateCommand.class);
        given(shipmentService.create(command)).willReturn(List.of());

        // when
        sut.create(command);

        // then
        then(deliveryOutboxRelayFacade).shouldHaveNoInteractions();
    }

    @Test
    void 출고하면_배송_시작_줄을_바로_보내고_택배사에_추적을_요청하고_출고_결과를_돌려준다() {
        // given
        given(shipmentService.dispatch(DISPATCH_COMMAND)).willReturn(DISPATCHED);

        // when
        ShipmentDispatchResult result = sut.dispatch(DISPATCH_COMMAND);

        // then
        assertThat(result).isEqualTo(DISPATCHED);
        then(deliveryOutboxRelayFacade).should().send(100L, NOW);
        then(carrierClient).should().requestTracking(1L, "CJ_LOGISTICS", "1234567890");
    }

    @Test
    void 출고가_실패하면_보내지도_택배사에_추적을_요청하지도_않는다() {
        // given
        given(shipmentService.dispatch(DISPATCH_COMMAND)).willThrow(new RestApiException(DeliveryErrorCode.TRACKING_NUMBER_DUPLICATED));

        // when & then
        assertThatThrownBy(() -> sut.dispatch(DISPATCH_COMMAND))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.TRACKING_NUMBER_DUPLICATED));
        then(deliveryOutboxRelayFacade).shouldHaveNoInteractions();
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

    @Test
    void 배송_완료를_반영하면_배송_완료_줄을_바로_보낸다() {
        // given
        given(shipmentService.deliver(5L)).willReturn(70L);

        // when
        sut.deliver(5L);

        // then
        then(deliveryOutboxRelayFacade).should().send(70L, NOW);
    }

    @Test
    void 대기열이_가득_차_거절되면_예외_없이_건수를_세고_나머지_줄은_계속_넘긴다() {
        // given
        ShipmentCreateCommand command = mock(ShipmentCreateCommand.class);
        given(shipmentService.create(command)).willReturn(List.of(70L, 71L));
        willThrow(new TaskRejectedException("대기열 가득")).given(deliveryOutboxRelayFacade).send(70L, NOW);

        // when & then
        assertThatCode(() -> sut.create(command)).doesNotThrowAnyException();
        then(deliveryOutboxRelayFacade).should().send(71L, NOW);
        assertThat(rejectedCount()).isEqualTo(1.0);
    }

    @Test
    void 출고_발송이_거절돼도_택배사_추적은_요청하고_출고_결과를_돌려준다() {
        // given
        given(shipmentService.dispatch(DISPATCH_COMMAND)).willReturn(DISPATCHED);
        willThrow(new TaskRejectedException("대기열 가득")).given(deliveryOutboxRelayFacade).send(any(), any());

        // when
        ShipmentDispatchResult result = sut.dispatch(DISPATCH_COMMAND);

        // then
        assertThat(result).isEqualTo(DISPATCHED);
        then(carrierClient).should().requestTracking(1L, "CJ_LOGISTICS", "1234567890");
        assertThat(rejectedCount()).isEqualTo(1.0);
    }

    private double rejectedCount() {
        return meterRegistry.get(DeliveryAsyncConfig.OUTBOX_SEND_DROPPED_METRIC)
                .tag("reason", DeliveryAsyncConfig.DROP_REASON_REJECTED)
                .counter()
                .count();
    }
}
