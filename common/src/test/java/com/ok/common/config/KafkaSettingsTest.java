package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 모든 앱이 가져다 쓰는 common-kafka.yaml의 값이 적용되는지 검증한다. 브로커 연결은 KafkaConnectionTest가 확인한다.
 * 설정 값만 바인딩해 보므로 Kafka 컨테이너와 자동 설정 없이 가볍게 띄운다.
 */
// Kafka에는 @DataRedisTest 같은 슬라이스가 없어, 이 클래스 자체를 설정으로 쓰고 KafkaProperties만 바인딩한다
// 이 클래스는 @Component가 아니라 AbstractIntegrationTest의 @ComponentScan(com.ok)에 잡히지 않는다
// 앱이 import하는 것과 같은 방식으로 읽는다
@SpringBootTest(classes = KafkaSettingsTest.class, properties = "spring.config.import=classpath:common-kafka.yaml")
@EnableConfigurationProperties(KafkaProperties.class)
class KafkaSettingsTest {

    @Autowired
    KafkaProperties sut;

    // .env를 새로 만들지 않은 팀원도 로컬 compose의 브로커 3대(127.0.0.1에만 열린 EXTERNAL 포트)에 바로 붙어야 한다
    @Test
    void 접속_주소를_정하지_않으면_로컬_브로커_3대를_쓴다() {
        // when
        List<String> actual = sut.getBootstrapServers();

        // then
        assertThat(actual).containsExactly("localhost:19092", "localhost:29092", "localhost:39092");
    }

    // 브로커의 min.insync.replicas=2는 프로듀서가 acks=all일 때만 의미가 있다. 값이 빠지거나 바뀌면 1대에만 저장돼도 성공으로 본다
    @Test
    void 발행은_모든_복사본_확인을_기다린다() {
        // when
        String actual = sut.getProducer().getAcks();

        // then
        assertThat(actual).isEqualTo("all");
    }

    // 운영과 로컬 app 컨테이너는 compose가 이 환경변수로 컨테이너 이름 주소를 넘긴다
    @Nested
    @TestPropertySource(properties = "KAFKA_BOOTSTRAP_SERVERS=kafka-1:9092,kafka-2:9092,kafka-3:9092")
    class 환경변수로_주소를_넘기면 {

        @Autowired
        KafkaProperties sut;

        @Test
        void 환경변수로_넘긴_접속_주소를_쓴다() {
            // when
            List<String> actual = sut.getBootstrapServers();

            // then
            assertThat(actual).containsExactly("kafka-1:9092", "kafka-2:9092", "kafka-3:9092");
        }
    }

}
