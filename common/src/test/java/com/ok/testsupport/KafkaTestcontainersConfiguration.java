package com.ok.testsupport;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 Kafka 브로커 컨테이너를 JVM 전체에서 하나만 띄워 모든 테스트 컨텍스트가 공유하게 한다 (#538).
 * <p>{@link TestcontainersConfiguration}(MySQL), {@link RedisTestcontainersConfiguration}과 같은 방식으로 컨테이너가 아니라 접속 정보({@link KafkaConnectionDetails})만 빈으로 등록한다.
 * 이유는 {@link TestcontainersConfiguration}에 적었다.
 * <p>통합 테스트는 {@link AbstractIntegrationTest}가 이 설정을 함께 가져오므로 따로 {@code @Import}하지 않는다.
 * 통합 테스트가 아닌 슬라이스 테스트만 직접 {@code @Import}한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    private static KafkaContainer kafka;

    @Bean
    KafkaConnectionDetails kafkaConnectionDetails(@Value("${KAFKA_VERSION:4.2.1}") String kafkaVersion) {
        KafkaContainer container = startKafka(kafkaVersion);
        return new KafkaConnectionDetails() {
            @Override
            public List<String> getBootstrapServers() {
                return List.of(container.getBootstrapServers());
            }
        };
    }

    // 운영·compose와 같은 apache/kafka 이미지다. 테스트는 브로커 1대라 복제 수 3을 요구하는 설정은 쓰지 않는다
    private static synchronized KafkaContainer startKafka(String kafkaVersion) {
        if (kafka == null) {
            KafkaContainer container = new KafkaContainer(DockerImageName.parse("apache/kafka:" + kafkaVersion));
            container.start();
            kafka = container;
        }
        return kafka;
    }

}
