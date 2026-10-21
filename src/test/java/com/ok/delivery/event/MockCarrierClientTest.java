package com.ok.delivery.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.timeout;

@ExtendWith(MockitoExtension.class)
class MockCarrierClientTest {

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    MockCarrierClient sut;

    @Test
    void 추적을_요청받으면_바로_알리지_않고_5초_뒤_배송_완료를_알린다() {
        // when
        sut.requestTracking(1L, "CJ_LOGISTICS", "1234567890");

        // then
        then(applicationEventPublisher).shouldHaveNoInteractions();
        then(applicationEventPublisher).should(timeout(7_000)).publishEvent(new CarrierDeliveredEvent(1L));
    }
}
