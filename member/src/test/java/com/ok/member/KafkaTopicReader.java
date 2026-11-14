package com.ok.member;

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
 * member가 보내는 토픽 하나에 실제로 나간 메시지를 키와 JSON 문자열 그대로 읽는 테스트용 컨슈머다 (#560, #587).
 * <p>{@code TestKafkaConsumer}는 {@code test-topic} 전용이라 따로 둔다. 앱의 컨슈머 설정(JSON 변환)을 거치지 않고 문자열로 읽어,
 * 받는 쪽이 보게 될 JSON을 그대로 확인한다.
 * <p>컨슈머 그룹 없이 파티션을 직접 맡고, 만들 때 각 파티션의 끝으로 옮겨 둔다. 그래서 만든 뒤에 나간 메시지만 읽고,
 * 그룹 배정을 기다리다 메시지를 놓치는 일이 없다. 다른 테스트의 메시지와 섞이지 않게 키로 골라 읽는다.
 * 회원·판매자 하위 도메인 테스트가 함께 쓰도록 모듈 최상위 테스트 패키지에 공개로 둔다.
 */
public class KafkaTopicReader implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    public KafkaTopicReader(List<String> bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", bootstrapServers),
                ConsumerConfig.CLIENT_ID_CONFIG, "member-topic-reader-" + UUID.randomUUID(),
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
     * 주어진 시간 동안 토픽을 읽어, 키가 {@code keyId}인 메시지의 값(JSON)을 모두 돌려준다.
     * 기다리는 동안 다시 보낸 메시지까지 모으려고 시간이 다 될 때까지 읽는다.
     */
    public List<String> readValuesFor(Long keyId, Duration duration) {
        Instant until = Instant.now().plus(duration);
        while (Instant.now().isBefore(until)) {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        }
        String key = String.valueOf(keyId);
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
