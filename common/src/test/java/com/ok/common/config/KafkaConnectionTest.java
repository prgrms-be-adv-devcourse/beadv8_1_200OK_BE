package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.ok.testsupport.KafkaTestcontainersConfiguration;

/**
 * 스타터와 자동 설정이 실제 Kafka와 연결되어 메시지를 주고받는지 검증한다.
 * <p>통합 테스트 규칙({@code AbstractIntegrationTest} 상속)의 예외다. common에는 애플리케이션 클래스가 없고,
 * Kafka에는 Redis의 {@code @DataRedisTest} 같은 슬라이스가 없어 Kafka 자동 설정만 올리는 가벼운 컨텍스트로 띄운다 (RedisConnectionTest와 같은 이유).
 * Kafka를 쓰는 후속 통합 테스트는 {@code AbstractIntegrationTest}를 상속하면 같은 컨테이너를 쓴다.
 */
// 토픽을 미리 만들지 않아도 첫 발행 때 자동으로 생긴다(토픽 자동 생성은 기본값 그대로 둠)
// 받는 쪽이 늦게 구독해도 앞서 보낸 메시지를 읽도록 처음부터 읽게 한다
@SpringBootTest(classes = KafkaConnectionTest.class, properties = "spring.kafka.consumer.auto-offset-reset=earliest")
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
@Import(KafkaTestcontainersConfiguration.class)
class KafkaConnectionTest {

    private static final String TOPIC = "kafka-connection-test";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Test
    void 보낸_메시지를_같은_토픽에서_받는다() throws Exception {
        // given
        kafkaTemplate.send(TOPIC, "ok").get(10, TimeUnit.SECONDS);

        // when
        String actual = receiveOne();

        // then
        assertThat(actual).isEqualTo("ok");
    }

    // 구독 직후에는 파티션 배정을 기다리느라 빈 결과가 올 수 있어, 메시지가 올 때까지 몇 번 더 받아 본다
    private String receiveOne() {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("kafka-connection-test", null)) {
            consumer.subscribe(List.of(TOPIC));
            for (int attempt = 0; attempt < 10; attempt++) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<String, String> record : records) {
                    return record.value();
                }
            }
        }
        return null;
    }

}
