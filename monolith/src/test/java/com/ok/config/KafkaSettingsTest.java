package com.ok.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * monolith가 application.yaml의 spring.config.import로 common-kafka.yaml을 가져오는지 검증한다.
 * 설정 값 자체는 common의 KafkaSettingsTest가 확인한다.
 */
// Kafka에는 슬라이스가 없고 전체 앱을 띄우면 DB가 필요해, 이 클래스 자체를 설정으로 쓰고 KafkaProperties만 바인딩한다
// application.yaml은 monolith 클래스패스에 있어 Spring Boot가 앱과 같은 방식으로 읽는다
@SpringBootTest(classes = KafkaSettingsTest.class)
@EnableConfigurationProperties(KafkaProperties.class)
class KafkaSettingsTest {

    @Autowired
    KafkaProperties sut;

    // import 줄이 빠지면 Spring 기본값(localhost:9092)으로 돌아가 로컬 compose의 브로커 3대에 붙지 못한다
    @Test
    void monolith는_공통_Kafka_설정을_가져온다() {
        // when
        List<String> actual = sut.getBootstrapServers();

        // then
        assertThat(actual).containsExactly("localhost:19092", "localhost:29092", "localhost:39092");
    }

}
