package com.ok.wallet.adapter.out.event;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 지갑이 Kafka로 보내는 메시지 한 건을 브로커의 저장 확인(acks=all)까지 최대 {@link #SEND_TIMEOUT} 기다려 보낸다.
 * 실패하면 호출하는 쪽이 준 문구로 예외를 던진다. 예외가 리스너까지 올라가야 같은 요청을 다시 받아 다시 보낸다.
 * KafkaTemplate은 스프링 부트가 yaml(spring.kafka)로 만든 것을 그대로 쓴다. 지갑 전용으로 새로 만들면 한 앱(monolith)에
 * KafkaTemplate이 둘이 되어 다른 모듈의 주입이 모호해질 수 있다.
 */
@Component
class WalletKafkaSender {
    // 저장 확인을 기다리는 최대 시간. 정상이면 1초 안에 끝난다. Kafka 기본값(delivery.timeout.ms 120초)까지 기다리면
    // 브로커 장애 때 리스너가 한 메시지에 오래 묶여 다시 시도까지 늦어진다
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Duration sendTimeout;

    @Autowired
    WalletKafkaSender(KafkaTemplate<String, Object> kafkaTemplate) {
        this(kafkaTemplate, SEND_TIMEOUT);
    }

    // 테스트가 시간 초과 경로를 10초씩 기다리지 않고 확인할 수 있게 대기 시간을 바꿔 넣는다
    WalletKafkaSender(KafkaTemplate<String, Object> kafkaTemplate, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

    /**
     * 메시지를 보내고 저장 확인을 기다린다.
     *
     * @param failureMessage 보내지 못했을 때 예외 문구. 어느 요청의 메시지인지 찾을 수 있게 식별자를 담는다
     * @throws IllegalStateException 보내지 못했거나 정해진 시간 안에 저장 확인이 오지 않았으면
     */
    void send(String topic, String key, Object message, String failureMessage) {
        try {
            kafkaTemplate.send(topic, key, message)
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(failureMessage, e.getCause());
        } catch (TimeoutException e) {
            // 기다리기만 그만둔 것이라 메시지는 뒤에서 계속 보내질 수 있다. 다시 보내 같은 메시지가 두 번 갈 수 있으므로
            // 받는 쪽이 같은 메시지를 여러 번 받아도 한 번만 반영하는 메시지에만 쓴다
            throw new IllegalStateException(failureMessage, e);
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 요청인지 남긴다
            throw new IllegalStateException(failureMessage, e);
        }
    }
}
