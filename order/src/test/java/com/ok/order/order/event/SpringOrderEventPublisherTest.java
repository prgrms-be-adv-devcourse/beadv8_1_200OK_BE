package com.ok.order.order.event;

import com.ok.common.event.OrderPaidEvent;
import com.ok.order.order.service.dto.PaidItemMessage;
import com.ok.order.order.service.dto.PaidOrderMessage;
import com.ok.order.order.service.dto.PaidSellerGroupMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SpringOrderEventPublisherTest {

    @Mock ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks SpringOrderEventPublisher sut;

    @Test
    void 결제_완료_메시지를_배송이_받는_결제_완료_이벤트로_바꿔_발행한다() {
        // given
        LocalDateTime paidAt = LocalDateTime.of(2026, 10, 21, 10, 0);
        PaidOrderMessage message = new PaidOrderMessage(
                10L, 1L, paidAt,
                "홍길동", "010-1234-5678", "06236", "서울시 강남구 테헤란로 1", "101호", "문 앞에 놓아 주세요",
                List.of(new PaidSellerGroupMessage(100L, 3_000L, 50_000L, 3_000L,
                        List.of(new PaidItemMessage(1L, "사과", "1kg", 2, 10_000L)))));

        // when
        sut.publishOrderPaidEvent(message);

        // then
        ArgumentCaptor<OrderPaidEvent> captor = ArgumentCaptor.forClass(OrderPaidEvent.class);
        then(applicationEventPublisher).should().publishEvent(captor.capture());
        OrderPaidEvent event = captor.getValue();
        assertSoftly(softly -> {
            softly.assertThat(event.orderId()).isEqualTo(10L);
            softly.assertThat(event.buyerId()).isEqualTo(1L);
            softly.assertThat(event.paidAt()).isEqualTo(paidAt);
            softly.assertThat(event.shippingAddress().addressDetail()).isEqualTo("101호");
            softly.assertThat(event.shippingAddress().deliveryNote()).isEqualTo("문 앞에 놓아 주세요");
            softly.assertThat(event.sellerOrders().get(0).sellerId()).isEqualTo(100L);
            softly.assertThat(event.sellerOrders().get(0).baseShippingFee()).isEqualTo(3_000L);
            softly.assertThat(event.sellerOrders().get(0).freeShippingThreshold()).isEqualTo(50_000L);
            softly.assertThat(event.sellerOrders().get(0).regionSurcharge()).isEqualTo(3_000L);
            softly.assertThat(event.sellerOrders().get(0).items().get(0).orderItemId()).isEqualTo(1L);
            softly.assertThat(event.sellerOrders().get(0).items().get(0).quantity()).isEqualTo(2);
        });
    }
}