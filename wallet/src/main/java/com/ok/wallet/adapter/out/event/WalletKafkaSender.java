package com.ok.wallet.adapter.out.event;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/**
 * 지갑이 Kafka로 보내는 메시지를 브로커의 저장 확인(acks=all)까지 최대 {@link #SEND_TIMEOUT} 기다려 보낸다.
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
        sendAll(List.of(topic), key, message, failedTopic -> failureMessage);
    }

    /**
     * 같은 메시지를 여러 토픽에 보내고, 모든 토픽의 저장 확인을 합쳐서 최대 {@link #SEND_TIMEOUT} 기다린다.
     * 토픽 이름을 옮기는 동안 옛 이름과 새 이름에 함께 보낼 때 쓴다. 한 토픽이라도 실패하면 예외라, 다시 받아 보낼 때
     * 모든 토픽에 다시 나간다. 받는 쪽이 같은 메시지를 여러 번 받아도 한 번만 반영하는 메시지에만 쓴다.
     *
     * @param failureMessage 보내지 못했을 때 예외 문구. 끝에 실패한 토픽 이름을 붙인다
     * @throws IllegalStateException 한 토픽이라도 보내지 못했거나 정해진 시간 안에 저장 확인이 오지 않았으면
     */
    void send(List<String> topics, String key, Object message, String failureMessage) {
        sendAll(topics, key, message, failedTopic -> failureMessage + ", topic=" + failedTopic);
    }

    private void sendAll(List<String> topics, String key, Object message, UnaryOperator<String> failureMessageOf) {
        // 모든 토픽에 먼저 보내 놓고 기다린다. 하나씩 보내고 기다리면 브로커 장애 때 기다리는 시간이 토픽 수만큼 늘어나,
        // 오류 처리기의 다시 시도 간격과 합친 시간이 소비자가 그룹에서 빠지는 기준(max.poll.interval.ms)을 넘을 수 있다
        long deadline = System.nanoTime() + sendTimeout.toNanos();
        List<CompletableFuture<SendResult<String, Object>>> results = new ArrayList<>();
        for (String topic : topics) {
            results.add(sendNow(topic, key, message, failureMessageOf.apply(topic)));
        }
        for (int i = 0; i < topics.size(); i++) {
            await(results.get(i), deadline, failureMessageOf.apply(topics.get(i)));
        }
    }

    private CompletableFuture<SendResult<String, Object>> sendNow(
            String topic, String key, Object message, String failureMessage) {
        try {
            return kafkaTemplate.send(topic, key, message);
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 요청인지 남긴다
            throw new IllegalStateException(failureMessage, e);
        }
    }

    private void await(CompletableFuture<SendResult<String, Object>> result, long deadline, String failureMessage) {
        try {
            // 앞 토픽을 기다리느라 쓴 시간을 빼고 남은 시간만 기다린다. 남은 시간이 없으면 이미 끝난 결과만 받아들인다
            result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        } catch (ExecutionException e) {
            // 감싼 실행 예외가 아니라 브로커가 준 원인을 남겨야 실패 기록에 실제 원인이 남는다
            throw new IllegalStateException(failureMessage, e.getCause());
        } catch (TimeoutException e) {
            // 기다리기만 그만둔 것이라 메시지는 뒤에서 계속 보내질 수 있다. 다시 보내 같은 메시지가 두 번 갈 수 있으므로
            // 받는 쪽이 같은 메시지를 여러 번 받아도 한 번만 반영하는 메시지에만 쓴다
            throw new IllegalStateException(failureMessage, e);
        } catch (RuntimeException e) {
            // 결과가 취소된 경우(CancellationException)도 어느 요청인지 남긴다
            throw new IllegalStateException(failureMessage, e);
        }
    }
}
