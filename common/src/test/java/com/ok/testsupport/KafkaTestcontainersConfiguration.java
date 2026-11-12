package com.ok.testsupport;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 Kafka 브로커 컨테이너만 켜는 설정이다 (#538). MySQL까지 함께 켜는 {@link TestcontainersConfiguration}과 달리 Kafka만 필요한 슬라이스 테스트가 쓴다.
 * <p>컨테이너는 JVM 전체에서 하나만 뜨고 {@link TestcontainersConfiguration}과도 공유한다. 그래서 두 설정을 한 컨텍스트에 함께 {@code @Import}하지 않는다.
 * 접속 정보({@link KafkaConnectionDetails})만 빈으로 등록하는 이유는 {@link TestcontainersConfiguration}과 같다.
 * <p>통합 테스트는 {@link AbstractIntegrationTest}가 {@link TestcontainersConfiguration}으로 이미 Kafka를 켜므로 따로 {@code @Import}하지 않는다.
 * <p>{@code @TestConfiguration}을 붙이지 않는다. {@link AbstractIntegrationTest}가 com.ok를 스캔해서 붙이면 모든 통합 테스트에 이 설정까지 켜지고,
 * {@link TestcontainersConfiguration}과 같은 접속 정보 빈이 둘이 되어 컨텍스트가 뜨지 않는다. 필요한 테스트가 {@code @Import}로만 켠다.
 */
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
    static synchronized KafkaContainer startKafka(String kafkaVersion) {
        if (kafka == null) {
            KafkaContainer container = new KafkaContainer(DockerImageName.parse("apache/kafka:" + kafkaVersion));
            container.start();
            kafka = container;
        }
        return kafka;
    }

}
