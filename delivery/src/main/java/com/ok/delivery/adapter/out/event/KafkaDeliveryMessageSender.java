package com.ok.delivery.adapter.out.event;

import com.ok.delivery.application.port.out.DeliveryMessageSender;
import com.ok.delivery.domain.DeliveryOutboxEventType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link DeliveryMessageSender}의 Kafka 구현체. outbox 줄의 종류에 맞는 토픽({@link DeliveryTopics})으로 저장해 둔 본문을 보낸다.
 * 한 건마다 브로커의 저장 확인(acks=all)을 최대 {@link #SEND_TIMEOUT}까지 기다리고, 실패하면 어느 줄인지 담은 예외를 던진다.
 * KafkaTemplate은 스프링 부트가 yaml(spring.kafka)로 만든 것을 그대로 쓴다. 모듈 전용으로 새로 만들면 한 앱(monolith)에
 * KafkaTemplate이 둘이 되어 다른 모듈의 주입이 모호해질 수 있다.
 */
@Component
class KafkaDeliveryMessageSender implements DeliveryMessageSender {

    // 스케줄러 스레드에서 기다리므로 Kafka 기본값(120초)까지 기다리면 같은 스레드의 다른 스케줄 작업이 밀린다.
    // 실패해도 줄이 PENDING으로 남아 다음 실행에서 다시 보내므로 오래 기다릴 이유가 없다(회원 outbox와 같음)
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final Duration sendTimeout;

    @Autowired
    KafkaDeliveryMessageSender(KafkaTemplate<String, Object> kafkaTemplate, JsonMapper jsonMapper) {
        this(kafkaTemplate, jsonMapper, SEND_TIMEOUT);
    }

    // 테스트가 시간 초과 경로를 10초씩 기다리지 않고 확인할 수 있게 대기 시간을 바꿔 넣는다
    KafkaDeliveryMessageSender(KafkaTemplate<String, Object> kafkaTemplate, JsonMapper jsonMapper, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void send(DeliveryOutboxEventType eventType, String messageKey, String payload) {
        try {
            // 값 직렬화기가 JSON 직렬화기라 문자열을 그대로 넘기면 따옴표로 한 번 더 감싼 문자열이 나간다. JSON 트리로 읽어 넘긴다
            JsonNode value = jsonMapper.readTree(payload);
            // 기다리지 않으면 브로커가 거절해도 outbox 줄이 "보냄"으로 바뀌어 다시 보내지 않는다
            kafkaTemplate.send(topicOf(eventType), messageKey, value)
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw sendFailed(eventType, messageKey, e);
        } catch (ExecutionException e) {
            throw sendFailed(eventType, messageKey, e.getCause());
        } catch (TimeoutException e) {
            // 기다리기만 그만둔 것이라 메시지는 뒤에서 계속 보내질 수 있다. 다시 보내면 두 번 가지만 주문은 중복을 무시한다(#603)
            throw sendFailed(eventType, messageKey, e);
        } catch (RuntimeException e) {
            // 본문을 읽지 못했거나 send()를 부르는 순간 난 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 줄인지 남긴다
            throw sendFailed(eventType, messageKey, e);
        }
    }

    private static String topicOf(DeliveryOutboxEventType eventType) {
        return switch (eventType) {
            case SHIPMENT_DISPATCHED -> DeliveryTopics.SHIPMENT_DISPATCHED;
        };
    }

    // 사용자 입력이 아니라 브로커 쪽 실패라 표준 예외를 쓴다 (exception.md: 값의 출처 기준)
    private static IllegalStateException sendFailed(DeliveryOutboxEventType eventType, String messageKey, Throwable cause) {
        return new IllegalStateException(
                "배송 Kafka 발행에 실패했습니다. eventType=" + eventType + ", messageKey=" + messageKey, cause);
    }
}
