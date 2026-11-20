package com.ok.delivery;

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
 * 토픽에 실제로 나간 메시지를 키와 값 문자열 그대로 읽는 테스트용 컨슈머다(회원 MemberRegisteredTopicReader와 같은 방식).
 * <p>앱의 컨슈머 설정(JSON 변환)을 거치지 않고 문자열로 읽어, 받는 쪽이 보게 될 JSON이나 DLT에 옮겨진 원본을 그대로 확인한다.
 * <p>컨슈머 그룹 없이 파티션을 직접 맡고, 만들 때 각 파티션의 끝으로 옮겨 둔다. 그래서 만든 뒤에 나간 메시지만 읽고,
 * 그룹 배정을 기다리다 메시지를 놓치는 일이 없다. 다른 테스트의 메시지와 섞이지 않게 키로 골라 읽는다.
 */
class DeliveryTopicReader implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    DeliveryTopicReader(List<String> bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", bootstrapServers),
                ConsumerConfig.CLIENT_ID_CONFIG, "delivery-topic-reader-" + UUID.randomUUID(),
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
     * 주어진 시간 동안 토픽을 읽어, 키가 {@code key}인 메시지의 값을 모두 돌려준다.
     * 기다리는 동안 다시 보낸 메시지까지 모으려고 시간이 다 될 때까지 읽는다.
     */
    List<String> readValuesFor(String key, Duration duration) {
        Instant until = Instant.now().plus(duration);
        while (Instant.now().isBefore(until)) {
            poll();
        }
        return valuesFor(key);
    }

    /**
     * 키가 {@code key}인 메시지가 처음 보일 때까지 읽고 그 값을 돌려준다. 시간 안에 오지 않으면 빈 목록이다.
     * 도착한 시각을 재는 테스트가 기다린 만큼만 쓰도록 찾는 즉시 돌아온다.
     */
    List<String> awaitValuesFor(String key, Duration timeout) {
        Instant until = Instant.now().plus(timeout);
        while (Instant.now().isBefore(until) && valuesFor(key).isEmpty()) {
            poll();
        }
        return valuesFor(key);
    }

    private void poll() {
        consumer.poll(Duration.ofMillis(200)).forEach(received::add);
    }

    private List<String> valuesFor(String key) {
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
