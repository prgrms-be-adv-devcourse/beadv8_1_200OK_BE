package com.ok.testsupport;

import com.ok.order.order.adapter.in.event.OrderKafkaConsumerConfig;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 주문 전용 Kafka 오류 처리 설정({@link OrderKafkaConsumerConfig})을 쓰는 테스트 리스너다 (#666).
 * 메시지의 {@code mode}에 따라 성공하거나 실패해, 다시 시도·실패 기록 규칙을 실제 Kafka로 확인한다.
 * 리스너는 컨슈머 스레드에서 돌아 동시성 컬렉션을 쓴다.
 */
@Component
public class OrderKafkaTestListener {

    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    @KafkaListener(
            topics = OrderKafkaTestConfiguration.LISTENER_TOPIC,
            groupId = OrderKafkaTestConfiguration.LISTENER_GROUP,
            containerFactory = OrderKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.testsupport.OrderKafkaTestMessage")
    void on(OrderKafkaTestMessage message) {
        int attempt = attempts.computeIfAbsent(message.id(), id -> new AtomicInteger()).incrementAndGet();
        switch (message.mode()) {
            case "invalid" -> throw new IllegalArgumentException("값이 틀린 메시지. id=" + message.id());
            case "fail" -> throw new IllegalStateException("처리 실패. id=" + message.id());
            case "flaky" -> {
                if (attempt == 1) {
                    throw new IllegalStateException("잠깐 실패. id=" + message.id());
                }
                processed.add(message.id());
            }
            default -> processed.add(message.id());
        }
    }

    public int attemptsOf(String id) {
        AtomicInteger count = attempts.get(id);
        return count == null ? 0 : count.get();
    }

    public boolean isProcessed(String id) {
        return processed.contains(id);
    }
}
