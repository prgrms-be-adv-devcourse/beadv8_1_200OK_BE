package com.ok.delivery.adapter.out.event;

import com.ok.delivery.application.dto.CarrierTrackingRequestedEvent;
import com.ok.delivery.application.dto.DeliveryOutboxRecordedEvent;
import com.ok.delivery.application.port.out.DeliveryOutboxRepository;
import com.ok.delivery.domain.DeliveryOutbox;
import com.ok.delivery.domain.DeliveryOutboxEventType;
import com.ok.delivery.domain.DeliveryOutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;

// 실제 Kafka로 나간 메시지는 DeliveryOutboxKafkaIntegrationTest가 본다. 여기서는 outbox에 남기는 값과 배송 모듈 안의 스프링 이벤트를 본다
@ExtendWith(MockitoExtension.class)
class OutboxDeliveryEventPublisherTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Long OUTBOX_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    DeliveryOutboxRepository deliveryOutboxRepository;

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    OutboxDeliveryEventPublisher sut;

    @BeforeEach
    void setUp() {
        sut = new OutboxDeliveryEventPublisher(deliveryOutboxRepository, applicationEventPublisher, JSON_MAPPER, CLOCK);
        // 저장하면 DB가 id를 채우는 것을 흉내 낸다. 값 검사에서 실패하는 테스트는 저장까지 가지 않아 lenient로 둔다
        lenient().when(deliveryOutboxRepository.save(any())).thenAnswer(invocation -> {
            DeliveryOutbox outbox = invocation.getArgument(0);
            ReflectionTestUtils.setField(outbox, "id", OUTBOX_ID);
            return outbox;
        });
    }

    @Test
    void 배송_생성을_알리면_orderId를_키로_본문을_outbox에_남기고_주문용_스프링_이벤트는_내지_않는다() {
        // given
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 19, 10, 0, 0, 123_456_000);

        // when
        sut.publishShipmentCreated(1L, 2L, 10L, List.of(101L, 102L), createdAt);

        // then
        DeliveryOutbox outbox = savedOutbox();
        assertThat(outbox).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(outbox.getEventType()).isEqualTo(DeliveryOutboxEventType.SHIPMENT_CREATED);
            softly.assertThat(outbox.getMessageKey()).isEqualTo("2");
            softly.assertThat(outbox.getStatus()).isEqualTo(DeliveryOutboxStatus.PENDING);
        });
        JsonNode payload = JSON_MAPPER.readTree(outbox.getPayload());
        assertThat(payload).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
                    "shipmentId", "orderId", "sellerId", "orderItemIds", "createdAt");
            softly.assertThat(payload.get("shipmentId").asLong()).isEqualTo(1L);
            softly.assertThat(payload.get("orderId").asLong()).isEqualTo(2L);
            softly.assertThat(payload.get("sellerId").asLong()).isEqualTo(10L);
            softly.assertThat(payload.get("orderItemIds").toString()).isEqualTo("[101,102]");
            softly.assertThat(payload.get("createdAt").asString()).isEqualTo("2026-10-19T10:00:00.123456");
        });
        then(applicationEventPublisher).should().publishEvent(new DeliveryOutboxRecordedEvent(OUTBOX_ID, NOW));
        then(applicationEventPublisher).shouldHaveNoMoreInteractions();
    }

    @Test
    void 배송_생성_시각이_null이면_예외가_발생하고_outbox에_남기지_않는다() {
        // when & then
        assertThatThrownBy(() -> sut.publishShipmentCreated(1L, 2L, 10L, List.of(101L), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("createdAt은 필수입니다.");
        then(deliveryOutboxRepository).shouldHaveNoInteractions();
    }

    @Test
    void 배송_시작을_알리면_orderId를_키로_본문을_outbox에_남긴다() {
        // given
        LocalDateTime shippedAt = LocalDateTime.of(2026, 10, 21, 10, 0);

        // when
        sut.publishShipmentDispatched(1L, 2L, 10L, "CJ_LOGISTICS", "1234567890", shippedAt);

        // then
        DeliveryOutbox outbox = savedOutbox();
        assertThat(outbox).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(outbox.getEventType()).isEqualTo(DeliveryOutboxEventType.SHIPMENT_DISPATCHED);
            softly.assertThat(outbox.getMessageKey()).isEqualTo("2");
            softly.assertThat(outbox.getStatus()).isEqualTo(DeliveryOutboxStatus.PENDING);
        });
        JsonNode payload = JSON_MAPPER.readTree(outbox.getPayload());
        assertThat(payload).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(payload.get("shipmentId").asLong()).isEqualTo(1L);
            softly.assertThat(payload.get("orderId").asLong()).isEqualTo(2L);
            softly.assertThat(payload.get("sellerId").asLong()).isEqualTo(10L);
            softly.assertThat(payload.get("carrier").asString()).isEqualTo("CJ_LOGISTICS");
            softly.assertThat(payload.get("trackingNumber").asString()).isEqualTo("1234567890");
            softly.assertThat(payload.get("shippedAt").asString()).isEqualTo("2026-10-21T10:00:00");
        });
    }

    @Test
    void 배송_시작을_알리면_택배사_추적_요청_이벤트와_즉시_발송_이벤트만_발행하고_주문용_스프링_이벤트는_내지_않는다() {
        // given
        LocalDateTime shippedAt = LocalDateTime.of(2026, 10, 21, 10, 0);

        // when
        sut.publishShipmentDispatched(1L, 2L, 10L, "CJ_LOGISTICS", "1234567890", shippedAt);

        // then
        then(applicationEventPublisher).should()
                .publishEvent(new CarrierTrackingRequestedEvent(1L, "CJ_LOGISTICS", "1234567890"));
        then(applicationEventPublisher).should().publishEvent(new DeliveryOutboxRecordedEvent(OUTBOX_ID, NOW));
        then(applicationEventPublisher).shouldHaveNoMoreInteractions();
    }

    @Test
    void 배송_시작_시각이_null이면_예외가_발생하고_outbox에_남기지도_이벤트를_발행하지도_않는다() {
        // when & then
        assertThatThrownBy(() -> sut.publishShipmentDispatched(1L, 2L, 10L, "CJ_LOGISTICS", "1234567890", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("shippedAt은 필수입니다.");
        then(deliveryOutboxRepository).shouldHaveNoInteractions();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void 배송_완료를_알리면_orderId를_키로_본문을_outbox에_남긴다() {
        // given
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 23, 15, 0, 0, 123_456_000);

        // when
        sut.publishShipmentDelivered(1L, 2L, 10L, deliveredAt);

        // then
        DeliveryOutbox outbox = savedOutbox();
        assertThat(outbox).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(outbox.getEventType()).isEqualTo(DeliveryOutboxEventType.SHIPMENT_DELIVERED);
            softly.assertThat(outbox.getMessageKey()).isEqualTo("2");
            softly.assertThat(outbox.getStatus()).isEqualTo(DeliveryOutboxStatus.PENDING);
        });
        JsonNode payload = JSON_MAPPER.readTree(outbox.getPayload());
        assertThat(payload).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(payload.propertyNames()).containsExactlyInAnyOrder("shipmentId", "orderId", "sellerId", "deliveredAt");
            softly.assertThat(payload.get("shipmentId").asLong()).isEqualTo(1L);
            softly.assertThat(payload.get("orderId").asLong()).isEqualTo(2L);
            softly.assertThat(payload.get("sellerId").asLong()).isEqualTo(10L);
            // 주문의 자동 구매확정 기준 시각이라 소수점 아래까지 그대로 나가야 한다
            softly.assertThat(payload.get("deliveredAt").asString()).isEqualTo("2026-10-23T15:00:00.123456");
        });
    }

    @Test
    void 배송_완료를_알리면_즉시_발송_이벤트만_발행하고_주문용_스프링_이벤트는_내지_않는다() {
        // given
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 23, 15, 0);

        // when
        sut.publishShipmentDelivered(1L, 2L, 10L, deliveredAt);

        // then
        then(applicationEventPublisher).should().publishEvent(new DeliveryOutboxRecordedEvent(OUTBOX_ID, NOW));
        then(applicationEventPublisher).shouldHaveNoMoreInteractions();
    }

    @Test
    void 배송_완료_시각이_null이면_예외가_발생하고_outbox에_남기지도_이벤트를_발행하지도_않는다() {
        // when & then
        assertThatThrownBy(() -> sut.publishShipmentDelivered(1L, 2L, 10L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("deliveredAt은 필수입니다.");
        then(deliveryOutboxRepository).shouldHaveNoInteractions();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    private DeliveryOutbox savedOutbox() {
        ArgumentCaptor<DeliveryOutbox> captor = ArgumentCaptor.forClass(DeliveryOutbox.class);
        then(deliveryOutboxRepository).should().save(captor.capture());
        return captor.getValue();
    }
}
