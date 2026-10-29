package com.ok.testsupport;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 Kafka 컨테이너를 JVM 전체에서 하나만 띄워 Kafka를 쓰는 테스트 컨텍스트가 공유하게 한다 (#318).
 * <p>{@link TestcontainersConfiguration}과 같은 방식으로 컨테이너가 아니라 접속 정보({@link KafkaConnectionDetails})만 빈으로 등록한다.
 * 컨테이너를 빈으로 두면 컨텍스트마다 컨테이너가 새로 뜨거나, 컨텍스트가 닫힐 때 다른 컨텍스트의 Kafka까지 멈춘다 (#151).
 * <p>로컬·운영은 브로커 3대지만 테스트는 1대로 띄운다. 테스트가 확인하는 연결과 송수신은 브로커 수와 결과가 같고,
 * 3대면 모듈마다 실행되는 통합 테스트의 기동 시간과 메모리가 3배가 된다. 1대일 때 필요한 복제 수 1 설정은 {@link KafkaContainer}가 해 준다.
 * 3대가 1대 고장을 버티는지는 로컬 compose에서 직접 확인한다.
 * <p>통합 테스트는 {@link AbstractIntegrationTest}가 이 설정을 함께 가져오므로 따로 {@code @Import}하지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    private static KafkaContainer kafka;

    /**
     * 이미지 버전은 로컬·운영 compose와 같은 {@code KAFKA_VERSION}을 쓴다. 앱의 kafka-clients 버전(4.2.1)과 같은 숫자다.
     */
    @Bean
    KafkaConnectionDetails kafkaConnectionDetails(@Value("${KAFKA_VERSION:4.2.1}") String kafkaVersion) {
        KafkaContainer container = startKafka(kafkaVersion);
        List<String> bootstrapServers = List.of(container.getBootstrapServers());
        return new KafkaConnectionDetails() {
            @Override
            public List<String> getBootstrapServers() {
                return bootstrapServers;
            }
        };
    }

    private static synchronized KafkaContainer startKafka(String kafkaVersion) {
        if (kafka == null) {
            KafkaContainer container = new KafkaContainer(DockerImageName.parse("apache/kafka:" + kafkaVersion));
            container.start();
            kafka = container;
        }
        return kafka;
    }

}
