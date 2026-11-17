package com.ok.order.order.adapter.out.event;

import com.ok.common.event.OrderPaidEvent;
import com.ok.order.order.application.dto.PaidItemMessage;
import com.ok.order.order.application.dto.PaidOrderMessage;
import com.ok.order.order.application.dto.PaidSellerGroupMessage;
import com.ok.order.order.application.port.in.OrderOutboxRecordUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SpringOrderEventPublisherTest {

    @Mock ApplicationEventPublisher applicationEventPublisher;
    @Mock OrderOutboxRecordUseCase outboxRecorder;
    @Spy JsonMapper jsonMapper = JsonMapper.builder().build();

    @InjectMocks SpringOrderEventPublisher sut;

    @Test
    void 주문_생성은_Spring_이벤트_없이_Kafka_outbox로만_보낸다() {
        // when
        sut.publishOrderCreatedEvent(10L, 1L, 23_000L, "사과 외 1건");

        // then
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        then(outboxRecorder).should().record(eq("order.order-created.v1"), eq("10"), payload.capture());
        then(applicationEventPublisher).shouldHaveNoInteractions();
        JsonNode json = jsonMapper.readTree(payload.getValue());
        assertSoftly(softly -> {
            softly.assertThat(json.get("orderId").asLong()).isEqualTo(10L);
            softly.assertThat(json.get("buyerId").asLong()).isEqualTo(1L);
            softly.assertThat(json.get("totalAmount").asLong()).isEqualTo(23_000L);
            softly.assertThat(json.get("orderSummary").asString()).isEqualTo("사과 외 1건");
            softly.assertThat(json.size()).isEqualTo(4);
        });
    }

    @Test
    void 구매확정은_Spring_이벤트_없이_Kafka_outbox로만_보낸다() {
        // when
        sut.publishOrderProductConfirmedEvent(10L, 1L, 100L, 20_000L, 3_000L);

        // then
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        then(outboxRecorder).should().record(eq("order.order-product-confirmed.v1"), eq("1"), payload.capture());
        then(applicationEventPublisher).shouldHaveNoInteractions();
        JsonNode json = jsonMapper.readTree(payload.getValue());
        assertSoftly(softly -> {
            softly.assertThat(json.get("orderId").asLong()).isEqualTo(10L);
            softly.assertThat(json.get("orderItemId").asLong()).isEqualTo(1L);
            softly.assertThat(json.get("sellerId").asLong()).isEqualTo(100L);
            softly.assertThat(json.get("totalItemPrice").asLong()).isEqualTo(20_000L);
            softly.assertThat(json.get("sellerShippingPrice").asLong()).isEqualTo(3_000L);
            softly.assertThat(json.size()).isEqualTo(5);
        });
    }

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