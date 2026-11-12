package com.ok.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 컨테이너 설정({@link KafkaTestcontainersConfiguration})과 테스트용 컨슈머({@link TestKafkaConsumer})가 앱 설정(common-kafka.yaml)으로 함께 동작하는지 검증한다 (#538).
 */
// MySQL 없이 Kafka만 필요해서 앱 전체가 아니라 Kafka 자동 설정과 테스트 지원 클래스만 켠다
@SpringBootTest(classes = TestKafkaConsumerTest.Config.class, properties = {
        "spring.config.import=classpath:common-kafka.yaml",
        "spring.application.name=test-kafka-consumer-test"
})
class TestKafkaConsumerTest {

    // @Configuration을 붙이지 않는다. 다른 모듈의 통합 테스트가 com.ok를 스캔해서 이 설정까지 켜지 않게 한다
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    @Import({KafkaTestcontainersConfiguration.class, TestKafkaConsumer.class})
    static class Config {

        @Bean
        NewTopic testTopic() {
            return new NewTopic(TestKafkaConsumer.TOPIC, 1, (short) 1);
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    TestKafkaConsumer sut;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    // 컨슈머가 파티션을 받기 전에 보낸 메시지는 latest라 받지 못하므로 받을 때까지 기다린다
    @BeforeEach
    void setUp() {
        await().atMost(TIMEOUT).until(sut::isAssigned);
        sut.clearMessages();
    }

    // 앱이 보낸 JSON 그대로를 문자열로 받아 모양을 확인할 수 있어야 한다
    @Test
    void 보낸_메시지를_JSON_문자열_그대로_받는다() throws Exception {
        // when
        kafkaTemplate.send(TestKafkaConsumer.TOPIC, "key", Map.of("message", "hello")).get(10, TimeUnit.SECONDS);

        // then
        assertThat(sut.getReceivedMessage(10, TimeUnit.SECONDS)).isEqualTo("{\"message\":\"hello\"}");
    }

    @Test
    void 메시지가_오지_않으면_제한_시간_뒤_null을_돌려준다() throws Exception {
        // when
        String actual = sut.getReceivedMessage(500, TimeUnit.MILLISECONDS);

        // then
        assertThat(actual).isNull();
    }

}
