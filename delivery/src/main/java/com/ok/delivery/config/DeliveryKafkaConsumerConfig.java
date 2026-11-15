package com.ok.delivery.config;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 배송이 Kafka로 받는 메시지의 오류 처리 규칙을 정한다. 배송 리스너만 이 설정({@link #LISTENER_CONTAINER_FACTORY})을 쓴다.
 * 기본 오류 처리기는 예외 종류와 관계없이 간격 없이 10번 다시 시도한 뒤 넘겨서, 값이 틀린 메시지를 헛되이 반복하고
 * DB가 몇 초만 끊겨도 메시지를 버린다. 그래서 둘을 나눈다(정산 {@code PayoutKafkaConsumerConfig}와 같은 규칙).
 * - {@link IllegalArgumentException}(값이 틀림)은 다시 시도하지 않고 바로 넘긴다.
 * - 그 밖의 예외는 1초부터 두 배씩 5번(1·2·4·8·16초, 약 31초) 다시 시도한다.
 * 끝까지 실패하거나 값이 틀린 메시지는 오류 처리기가 ERROR 로그를 남기고 다음 메시지로 넘어간다.
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryKafkaConsumerConfig {

    // 배송 @KafkaListener의 containerFactory 속성에 쓰는 빈 이름
    // 기본 이름(kafkaListenerContainerFactory)과 다르게 둬야 Spring Boot 기본 설정이 그대로 만들어져 다른 모듈은 영향을 받지 않는다
    public static final String LISTENER_CONTAINER_FACTORY = "deliveryKafkaListenerContainerFactory";

    static final int MAX_RETRIES = 5;
    static final long INITIAL_INTERVAL_MILLIS = 1_000L;
    static final double MULTIPLIER = 2.0;

    @Bean(LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> deliveryKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        // 역직렬화·그룹 같은 받기 설정은 Spring Boot 설정(application.yaml)을 그대로 쓰고, 오류 처리기만 바꾼다
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(deliveryErrorHandler());
        return factory;
    }

    // 테스트에서 규칙을 확인할 수 있게 빈 메서드와 나눠 둔다
    static DefaultErrorHandler deliveryErrorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deliveryBackOff());
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

    static ExponentialBackOffWithMaxRetries deliveryBackOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MILLIS);
        backOff.setMultiplier(MULTIPLIER);
        return backOff;
    }
}
