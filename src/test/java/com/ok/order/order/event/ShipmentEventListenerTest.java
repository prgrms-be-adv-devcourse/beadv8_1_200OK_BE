package com.ok.order.order.event;

import com.ok.common.event.ShipmentDeliveredEvent;
import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.order.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ShipmentEventListenerTest {

    @Mock OrderService orderService;

    @InjectMocks ShipmentEventListener sut;

    @Test
    void 배송_시작_이벤트를_받으면_주문과_판매자로_배송_시작을_반영한다() {
        // given
        ShipmentDispatchedEvent event = new ShipmentDispatchedEvent(
                1L, 10L, 100L, "CJ_LOGISTICS", "1234567890", LocalDateTime.of(2026, 10, 20, 9, 0));

        // when
        sut.on(event);

        // then
        then(orderService).should().startShipping(10L, 100L);
    }

    @Test
    void 배송_완료_이벤트를_받으면_이벤트의_배송_완료_시각을_넘긴다() {
        // given
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 22, 15, 0);
        ShipmentDeliveredEvent event = new ShipmentDeliveredEvent(1L, 10L, 100L, deliveredAt);

        // when
        sut.on(event);

        // then
        then(orderService).should().completeDelivery(10L, 100L, deliveredAt);
    }
}