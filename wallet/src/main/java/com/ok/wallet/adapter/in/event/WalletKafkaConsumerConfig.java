package com.ok.wallet.adapter.in.event;

import com.ok.wallet.application.port.in.MessageFailureUseCase;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지갑이 Kafka로 받는 메시지의 오류 처리 규칙을 정한다(#579).
 * 지갑 리스너만 이 설정({@link #LISTENER_CONTAINER_FACTORY})을 쓰고, 다른 모듈은 자기 설정이나 Spring Boot 기본 설정을 그대로 쓴다.
 * <p>
 * 기본 오류 처리기는 간격 없이 10번 시도한 뒤 로그만 남기고 넘겨, DB가 몇 초 끊기면 정산금 입금 같은 돈 메시지가 사라진다.
 * 이 설정은 메시지를 넘기지 않고 지갑 실패 기록 표에 남긴다.
 * - {@link IllegalArgumentException}(값이 틀림)과 JSON을 읽지 못한 메시지는 다시 해도 같으므로 바로 기록한다.
 * - 그 밖의 예외는 1초부터 두 배씩 3번(1·2·4초) 다시 시도한 뒤 기록한다.
 * - 기록마저 실패하면 오프셋을 옮기지 않아 같은 메시지를 다시 받는다.
 * 실패를 Kafka 실패 토픽이 아니라 DB에 두는 이유는 Kafka가 멈춰도 남고, 같은 토픽을 받는 다른 모듈에 다시 보내지 않으며, SQL로 바로 찾을 수 있어서다.
 */
// 모듈 안 위치: Kafka 타입을 쓰고 입력 포트(실패 기록)를 부르는 설정이라 입력 어댑터인 adapter.in.event에 둔다
@Configuration(proxyBeanMethods = false)
public class WalletKafkaConsumerConfig {

    // 지갑 @KafkaListener의 containerFactory 속성에 쓰는 빈 이름
    // 기본 이름(kafkaListenerContainerFactory)과 다르게 둬야 Spring Boot 기본 설정이 그대로 만들어져 다른 모듈은 영향을 받지 않는다
    public static final String LISTENER_CONTAINER_FACTORY = "walletKafkaListenerContainerFactory";

    // 일시적 실패를 다시 시도하는 횟수와 첫 간격. 1초부터 두 배씩 3번 = 1+2+4 = 약 7초
    // 정산·환불(약 31초)보다 짧은 이유: 지갑은 끝내 실패해도 메시지를 버리지 않고 기록하므로 오래 붙잡아 뒤 메시지를 기다리게 할 까닭이 적다
    static final int MAX_RETRIES = 3;
    static final long INITIAL_INTERVAL_MILLIS = 1_000L;
    static final double MULTIPLIER = 2.0;

    @Bean(LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> walletKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            MessageFailureUseCase messageFailureUseCase,
            JsonMapper jsonMapper) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        // 역직렬화·그룹 같은 받기 설정은 Spring Boot 설정(common-kafka.yaml)을 그대로 쓰고, 오류 처리기만 바꾼다
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(walletErrorHandler(
                new WalletMessageFailureRecoverer(messageFailureUseCase, jsonMapper), walletBackOff()));
        return factory;
    }

    // 테스트에서 규칙과 기록 실패 때의 동작을 Kafka 없이 확인할 수 있게 빈 메서드와 나눠 둔다
    static DefaultErrorHandler walletErrorHandler(ConsumerRecordRecoverer recoverer, BackOff backOff) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // 값이 틀렸다는 예외는 같은 메시지로 다시 해도 결과가 같아 바로 기록한다. JSON을 읽지 못한 오류는 기본으로 다시 시도하지 않는다
        // 다른 모듈이 보낸 값의 오류는 표준 예외를 던지기로 해서(exception.md) 입구 전용 예외를 따로 두지 않는다
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

    static ExponentialBackOffWithMaxRetries walletBackOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MILLIS);
        backOff.setMultiplier(MULTIPLIER);
        return backOff;
    }
}
