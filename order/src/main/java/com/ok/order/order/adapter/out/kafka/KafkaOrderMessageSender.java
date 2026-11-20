package com.ok.order.order.adapter.out.kafka;

import com.ok.order.order.application.port.out.OrderMessageSender;
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
 * {@link OrderMessageSender}의 Kafka 구현체. outbox에 저장된 토픽·키·본문 그대로 보낸다 (#666).
 * 한 건마다 브로커의 저장 확인(acks=all)을 최대 {@link #SEND_TIMEOUT}까지 기다리고, 실패하면 토픽·키를 담은 예외를 던진다.
 * KafkaTemplate은 스프링 부트가 yaml(spring.kafka, common-kafka.yaml)로 만든 것을 그대로 쓴다. 모듈 전용으로 새로 만들면
 * 한 앱(monolith)에 KafkaTemplate이 둘이 되어 다른 모듈의 주입이 모호해질 수 있다(회원과 같음).
 */
@Component
class KafkaOrderMessageSender implements OrderMessageSender {

    // 저장 확인을 기다리는 최대 시간. 재발송이 스케줄러 스레드에서 기다리므로, Kafka 기본값(delivery.timeout.ms 120초)까지
    // 기다리면 브로커 장애 때 같은 스레드를 쓰는 다른 정기 작업(결제 대기 정리 등)이 오래 밀린다. 정상이면 1초 안에 끝난다.
    // 실패해도 outbox 줄이 PENDING으로 남아 다음 재발송에서 다시 보내므로 오래 기다릴 이유가 없다
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final Duration sendTimeout;

    @Autowired
    KafkaOrderMessageSender(KafkaTemplate<String, Object> kafkaTemplate, JsonMapper jsonMapper) {
        this(kafkaTemplate, jsonMapper, SEND_TIMEOUT);
    }

    // 테스트가 시간 초과 경로를 10초씩 기다리지 않고 확인할 수 있게 대기 시간을 바꿔 넣는다
    KafkaOrderMessageSender(KafkaTemplate<String, Object> kafkaTemplate, JsonMapper jsonMapper, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void send(String topic, String messageKey, String payload) {
        String failureMessage = "주문 Kafka 발행에 실패했습니다. topic=" + topic + ", key=" + messageKey;
        try {
            // 저장해 둔 JSON을 트리로 읽어 보낸다. 문자열 그대로 보내면 JSON 직렬화기가 따옴표로 한 번 더 감싸
            // 받는 쪽이 객체가 아니라 문자열을 받게 된다
            JsonNode message = jsonMapper.readTree(payload);
            // 저장 확인을 기다린다. 기다리지 않으면 브로커가 거절해도 outbox 줄이 "보냄"으로 바뀌어 다시 보내지 않는다.
            // 이 시간 제한은 send() 뒤의 기다림만 줄인다. send() 안에서 Kafka 주소를 찾느라 기다리는 시간(max.block.ms 기본 60초)은
            // producer 설정이라 그대로다(회원·환불과 같은 한계)
            kafkaTemplate.send(topic, messageKey, message).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(failureMessage, e.getCause());
        } catch (TimeoutException e) {
            // 기다리기만 그만둔 것이라 메시지는 뒤에서 계속 보내질 수 있다. 그러면 다시 보낼 때 같은 메시지가 두 번 가지만,
            // 받는 쪽은 업무 키로 중복을 거른다
            throw new IllegalStateException(failureMessage, e);
        } catch (RuntimeException e) {
            // 본문을 읽지 못하거나 send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 토픽·키를 남긴다
            throw new IllegalStateException(failureMessage, e);
        }
    }
}
