package com.ok.testsupport;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 주문이 보낸 토픽 하나에 실제로 나간 메시지를 키와 JSON 문자열 그대로 읽는 테스트용 컨슈머다 (#666, 회원 KafkaTopicReader와 같은 방식).
 * 앱의 컨슈머 설정(JSON 변환)을 거치지 않고 문자열로 읽어, 받는 쪽이 보게 될 JSON을 그대로 확인한다.
 * 컨슈머 그룹 없이 파티션을 직접 맡고 만들 때 끝으로 옮겨 둔다. 그래서 만든 뒤에 나간 메시지만 읽고, 키로 골라 다른 테스트와 섞이지 않는다.
 */
public class OrderKafkaTopicReader implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    public OrderKafkaTopicReader(List<String> bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", bootstrapServers),
                ConsumerConfig.CLIENT_ID_CONFIG, "order-topic-reader-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        // seekToEnd는 다음 poll이나 position 때 반영된다. 지금 위치를 확정해 두지 않으면 이 뒤에 나간 메시지를 건너뛸 수 있다
        partitions.forEach(consumer::position);
    }

    /**
     * 주어진 시간 동안 토픽을 읽어, 키가 {@code key}인 메시지의 값(JSON)을 모두 돌려준다.
     * 기다리는 동안 다시 보낸 메시지까지 모으려고 시간이 다 될 때까지 읽는다.
     */
    public List<String> readValuesFor(String key, Duration duration) {
        Instant until = Instant.now().plus(duration);
        while (Instant.now().isBefore(until)) {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        }
        return received.stream()
                .filter(record -> key.equals(record.key()))
                .map(ConsumerRecord::value)
                .toList();
    }

    @Override
    public void close() {
        consumer.close();
    }
}
