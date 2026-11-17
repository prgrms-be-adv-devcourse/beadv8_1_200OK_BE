package com.ok.testsupport;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 주문 Kafka 발행·수신 기반(#666)을 실제 메시지 없이 확인하려고 쓰는 테스트 전용 토픽을 앱이 시작할 때 만든다.
 * 토픽이 없으면 테스트 리스너가 구독한 뒤 토픽이 생겨도 메타데이터를 다시 읽을 때(기본 5분)까지 메시지를 받지 못한다.
 * 통합 테스트({@code AbstractIntegrationTest}의 {@code com.ok} 스캔)에만 올라가고, 주문 패키지만 스캔하는 모듈 테스트에는 올라가지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class OrderKafkaTestConfiguration {

    public static final String OUTBOX_TOPIC = "order.test-outbox.v1";
    public static final String LISTENER_TOPIC = "order.test-listener.v1";
    public static final String LISTENER_GROUP = "order.test-listener";

    @Bean
    NewTopic orderTestOutboxTopic() {
        return TopicBuilder.name(OUTBOX_TOPIC).build();
    }

    @Bean
    NewTopic orderTestListenerTopic() {
        return TopicBuilder.name(LISTENER_TOPIC).build();
    }
}
