package com.ok.delivery.adapter.out.event;

import com.ok.delivery.domain.DeliveryOutboxEventType;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// 성공 경로(실제 Kafka에 나간 키와 JSON)는 DeliveryOutboxKafkaIntegrationTest가 본다.
// 실제 브로커로는 거절·시간 초과·중단을 만들기 어려워 KafkaTemplate을 Mockito로 대신한다
@ExtendWith(MockitoExtension.class)
class KafkaDeliveryMessageSenderTest {

    private static final String KEY = "2";
    private static final String PAYLOAD = "{\"shipmentId\":1,\"orderId\":2,\"sellerId\":10,\"carrier\":\"CJ_LOGISTICS\","
            + "\"trackingNumber\":\"1234567890\",\"shippedAt\":\"2026-10-21T10:00:00\"}";
    private static final String FAILED_MESSAGE = "배송 Kafka 발행에 실패했습니다. eventType=SHIPMENT_DISPATCHED, messageKey=2";
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    KafkaDeliveryMessageSender sut;

    @BeforeEach
    void setUp() {
        sut = new KafkaDeliveryMessageSender(kafkaTemplate, JsonMapper.builder().build(), SHORT_TIMEOUT);
    }

    // 중단 테스트가 남긴 중단 표시가 다른 테스트로 넘어가지 않게 지운다
    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void 브로커가_거절하면_종류와_키를_담은_예외를_던지고_원인을_남긴다() {
        // given
        KafkaException rejected = new KafkaException("브로커 거절");
        givenSendReturns(CompletableFuture.failedFuture(rejected));

        // when & then
        assertThatThrownBy(() -> sut.send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, KEY, PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE)
                .hasCause(rejected);
    }

    @Test
    void 정해진_시간_안에_확인이_오지_않으면_종류와_키를_담은_예외를_던진다() {
        // given
        givenSendReturns(new CompletableFuture<>());

        // when & then
        assertThatThrownBy(() -> sut.send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, KEY, PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void 기다리는_중_스레드가_중단되면_예외를_던지고_중단_표시를_되살린다() {
        // given: 이미 중단 표시가 있으면 끝나지 않은 결과를 기다리는 순간 InterruptedException이 난다
        givenSendReturns(new CompletableFuture<>());
        Thread.currentThread().interrupt();

        // when & then
        assertThatThrownBy(() -> sut.send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, KEY, PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 저장된_본문이_JSON이_아니면_보내지_않고_종류와_키를_담은_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> sut.send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, KEY, "깨진 본문"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE);
        then(kafkaTemplate).shouldHaveNoInteractions();
    }

    // 토픽이나 키가 다르면 given이 맞지 않아 테스트가 실패한다
    private void givenSendReturns(CompletableFuture<SendResult<String, Object>> result) {
        given(kafkaTemplate.send(eq(DeliveryTopics.SHIPMENT_DISPATCHED), eq(KEY), any()))
                .willReturn(result);
    }
}
