package com.ok.delivery.event;

import com.ok.common.event.ShipmentDispatchedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SpringDeliveryEventPublisherTest {

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    SpringDeliveryEventPublisher sut;

    @Test
    void 배송_시작을_알리면_받은_값으로_배송_시작_이벤트를_만들어_발행한다() {
        // given
        LocalDateTime shippedAt = LocalDateTime.of(2026, 10, 21, 10, 0);

        // when
        sut.publishShipmentDispatched(1L, 2L, 10L, "CJ_LOGISTICS", "1234567890", shippedAt);

        // then
        then(applicationEventPublisher).should()
                .publishEvent(new ShipmentDispatchedEvent(1L, 2L, 10L, "CJ_LOGISTICS", "1234567890", shippedAt));
    }

    @Test
    void 배송_시작_시각이_null이면_예외가_발생하고_이벤트를_발행하지_않는다() {
        // when & then
        assertThatThrownBy(() -> sut.publishShipmentDispatched(1L, 2L, 10L, "CJ_LOGISTICS", "1234567890", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("shippedAt은 필수입니다.");
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

}
