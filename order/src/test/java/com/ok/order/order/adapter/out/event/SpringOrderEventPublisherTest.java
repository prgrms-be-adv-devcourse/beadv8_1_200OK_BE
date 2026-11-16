package com.ok.order.order.adapter.out.event;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SpringOrderEventPublisherTest {

    @Mock OrderOutboxRecordUseCase outboxRecorder;
    @Spy JsonMapper jsonMapper = JsonMapper.builder().build();

    @InjectMocks SpringOrderEventPublisher sut;

    @Test
    void 주문_생성은_지갑이_받는_모양으로_Kafka_outbox에_남긴다() {
        // when
        sut.publishOrderCreatedEvent(10L, 1L, 23_000L, "사과 외 1건");

        // then
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        then(outboxRecorder).should().record(eq("order.order-created.v1"), eq("10"), payload.capture());
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
    void 구매확정은_정산이_받는_모양으로_Kafka_outbox에_남긴다() {
        // when
        sut.publishOrderProductConfirmedEvent(10L, 1L, 100L, 20_000L, 3_000L);

        // then
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        then(outboxRecorder).should().record(eq("order.order-product-confirmed.v1"), eq("1"), payload.capture());
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
    void 결제_완료는_배송이_받는_모양으로_Kafka_outbox에_남긴다() {
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
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        then(outboxRecorder).should().record(eq("order.order-paid.v1"), eq("10"), payload.capture());
        JsonNode json = jsonMapper.readTree(payload.getValue());
        JsonNode address = json.get("shippingAddress");
        JsonNode sellerOrder = json.get("sellerOrders").get(0);
        JsonNode item = sellerOrder.get("items").get(0);
        assertSoftly(softly -> {
            softly.assertThat(json.get("orderId").asLong()).isEqualTo(10L);
            softly.assertThat(json.get("buyerId").asLong()).isEqualTo(1L);
            // 배송과 약속한 시각 형식: 오프셋 없는 ISO-8601
            softly.assertThat(json.get("paidAt").asString()).isEqualTo("2026-10-21T10:00:00");
            softly.assertThat(address.get("recipientName").asString()).isEqualTo("홍길동");
            softly.assertThat(address.get("recipientPhone").asString()).isEqualTo("010-1234-5678");
            // 주문 안의 zipCode를 배송과 약속한 이름 postalCode로 보낸다
            softly.assertThat(address.get("postalCode").asString()).isEqualTo("06236");
            softly.assertThat(address.has("zipCode")).isFalse();
            softly.assertThat(address.get("addressDetail").asString()).isEqualTo("101호");
            softly.assertThat(address.get("deliveryNote").asString()).isEqualTo("문 앞에 놓아 주세요");
            softly.assertThat(sellerOrder.get("sellerId").asLong()).isEqualTo(100L);
            softly.assertThat(sellerOrder.get("baseShippingFee").asLong()).isEqualTo(3_000L);
            softly.assertThat(sellerOrder.get("freeShippingThreshold").asLong()).isEqualTo(50_000L);
            softly.assertThat(sellerOrder.get("regionSurcharge").asLong()).isEqualTo(3_000L);
            softly.assertThat(item.get("orderItemId").asLong()).isEqualTo(1L);
            softly.assertThat(item.get("productName").asString()).isEqualTo("사과");
            softly.assertThat(item.get("optionName").asString()).isEqualTo("1kg");
            softly.assertThat(item.get("quantity").asInt()).isEqualTo(2);
            softly.assertThat(item.get("unitPrice").asLong()).isEqualTo(10_000L);
        });
    }
}
