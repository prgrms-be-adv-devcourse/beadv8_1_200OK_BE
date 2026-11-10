package com.ok.payout.config;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 정산이 Kafka로 받는 메시지의 오류 처리 규칙을 정한다(#421).
 * 정산 리스너만 이 설정({@link #LISTENER_CONTAINER_FACTORY})을 쓰고, 다른 모듈은 Spring Boot 기본 설정을 그대로 쓴다.
 * <p>
 * 기본 오류 처리기는 예외 종류와 관계없이 간격 없이 10번 다시 시도한 뒤 넘긴다. 그래서 두 가지가 문제였다.
 * - 값이 틀린 메시지: 다시 받아도 같은 값이라 결과가 같은데 10번 다시 처리한다.
 * - 일시적 실패(DB 끊김 등): 몇 밀리초 만에 시도를 다 써서, DB가 몇 초만 끊겨도 구매확정 메시지를 버린다.
 * 이 설정은 둘을 나눈다.
 * - {@link IllegalArgumentException}(입구 검사가 "값이 틀림"으로 판단한 것)은 다시 시도하지 않고 바로 넘긴다.
 * - 그 밖의 예외는 1초부터 두 배씩 5번(1·2·4·8·16초, 약 31초) 다시 시도한다.
 * 끝까지 실패하거나 값이 틀린 메시지는 오류 처리기가 ERROR 로그를 남기고 다음 메시지로 넘어간다.
 * <p>
 * 끝까지 실패한 메시지의 보관(DLT)·알림·다시 처리는 팀 공통 실패 처리에서 정한다(#388).
 * 그때는 이 처리기의 "넘김"만 DLT로 보내도록 바꾸고, 어떤 오류를 다시 시도할지는 정산 메시지 값을 아는 정산이 정한 이 규칙을 그대로 쓴다.
 * 팀 규칙이나 공통 설정이 정해지면 그에 따라 이 클래스를 맞추거나 지운다.
 */
@Configuration(proxyBeanMethods = false)
public class PayoutKafkaConsumerConfig {

    // 정산 @KafkaListener의 containerFactory 속성에 쓰는 빈 이름
    // 기본 이름(kafkaListenerContainerFactory)과 다르게 둬야 Spring Boot 기본 설정이 그대로 만들어져 다른 모듈은 영향을 받지 않는다
    public static final String LISTENER_CONTAINER_FACTORY = "payoutKafkaListenerContainerFactory";

    // 일시적 실패를 다시 시도하는 횟수와 첫 간격. 1초부터 두 배씩 5번 = 1+2+4+8+16 = 약 31초 동안 버틴다
    static final int MAX_RETRIES = 5;
    static final long INITIAL_INTERVAL_MILLIS = 1_000L;
    static final double MULTIPLIER = 2.0;

    @Bean(LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> payoutKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        // 역직렬화·그룹 같은 받기 설정은 Spring Boot 설정(application.yaml)을 그대로 쓰고, 오류 처리기만 바꾼다
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(payoutErrorHandler());
        return factory;
    }

    // 테스트에서 규칙(다시 시도하지 않는 예외, 다시 시도 간격)을 확인할 수 있게 빈 메서드와 나눠 둔다
    static DefaultErrorHandler payoutErrorHandler() {
        // TODO(#388): 지금은 넘긴 메시지가 ERROR 로그에만 남는다. 팀 DLT가 생기면 넘길 때 DLT로 보내도록 바꾼다
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(payoutBackOff());
        // 입구 검사가 값이 틀렸다고 판단하면 던지는 예외. 다시 받아도 같은 값이라 다시 시도하지 않는다
        // 서비스·도메인·라이브러리에서 난 IllegalArgumentException도 일부러 포함한다. "인자가 틀림"은 같은 메시지로 다시 해도 결과가 같다
        // 입구 전용 예외를 따로 만들지 않는 이유: 다른 모듈이 보낸 값의 오류는 표준 예외를 던진다(exception.md 15줄)
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

    static ExponentialBackOffWithMaxRetries payoutBackOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MILLIS);
        backOff.setMultiplier(MULTIPLIER);
        return backOff;
    }
}
