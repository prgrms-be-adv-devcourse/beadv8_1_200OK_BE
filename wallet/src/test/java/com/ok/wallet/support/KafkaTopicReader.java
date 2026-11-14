package com.ok.wallet.support;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.ConsumerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * 지갑이 보낸 Kafka 메시지를 JSON 문자열 그대로 읽는다. 받는 모듈이 읽을 글자 그대로 필드 이름과 값을 확인하려는 것이다.
 * 앱의 받기 설정(접속 주소)을 쓰되 그룹은 매번 새로 만들어 토픽을 처음부터 읽는다. 토픽에는 앞 시험의 메시지가 남으므로 키로 거른다.
 */
public class KafkaTopicReader {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ConsumerFactory<?, ?> consumerFactory;

    public KafkaTopicReader(ConsumerFactory<?, ?> consumerFactory) {
        this.consumerFactory = consumerFactory;
    }

    /**
     * 키가 같은 메시지가 기대한 개수만큼 올 때까지 읽어 값을 보낸 순서대로 돌려준다.
     */
    public List<String> valuesWithKey(String topic, String key, int expectedCount) {
        Map<String, Object> config = new HashMap<>(consumerFactory.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "wallet-test-reader-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            await().atMost(TIMEOUT).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        values.add(record.value());
                    }
                }
                return values.size() >= expectedCount;
            });
        }
        return values;
    }
}
