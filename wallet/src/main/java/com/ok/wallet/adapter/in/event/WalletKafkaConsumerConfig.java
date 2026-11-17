package com.ok.wallet.adapter.in.event;

import com.ok.wallet.application.port.in.MessageFailureUseCase;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeoutException;

/**
 * 지갑이 Kafka로 받는 메시지의 오류 처리 규칙을 정한다(#579).
 * 지갑 리스너만 이 설정({@link #LISTENER_CONTAINER_FACTORY})을 쓰고, 다른 모듈은 자기 설정이나 Spring Boot 기본 설정을 그대로 쓴다.
 * <p>
 * 기본 오류 처리기는 간격 없이 10번 시도한 뒤 로그만 남기고 넘겨, DB가 몇 초 끊기면 정산금 입금 같은 돈 메시지가 사라진다.
 * 이 설정은 메시지를 넘기지 않고 지갑 실패 기록 표에 남긴다.
 * - {@link IllegalArgumentException}(값이 틀림)과 JSON을 읽지 못한 메시지는 다시 해도 같으므로 바로 기록한다.
 * - 결과를 Kafka로 보내다 잠깐 실패하면 1초부터 두 배씩, 최대 1분 간격으로 30분까지 다시 시도한 뒤 기록한다.
 *   Kafka가 없으면 결과를 보낼 수 없으므로, DB 장애 때처럼 돌아올 때까지 기다린다.
 * - 그 밖의 예외는 1초부터 두 배씩 3번(1·2·4초) 다시 시도한 뒤 기록한다.
 * - 기록마저 실패하면 오프셋을 옮기지 않아 같은 메시지를 다시 받는다.
 * 실패를 Kafka 실패 토픽이 아니라 DB에 두는 이유는 Kafka가 멈춰도 남고, 같은 토픽을 받는 다른 모듈에 다시 보내지 않으며, SQL로 바로 찾을 수 있어서다.
 */
// 모듈 안 위치: Kafka 타입을 쓰고 입력 포트(실패 기록)를 부르는 설정이라 입력 어댑터인 adapter.in.event에 둔다
@Slf4j
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

    // 결과 보내기가 잠깐 실패했을 때의 간격. 1초부터 두 배씩 늘려 1분에서 멈추고, 간격의 합이 30분이 되면 기록한다
    // 30분까지인 이유: 그 뒤에는 주문이 결제 만료를 요청해 지갑이 처음 결과를 다시 보내므로 더 붙잡을 까닭이 없다
    // 최대 1분인 이유: 오류 처리기는 소비자 스레드에서 간격만큼 잠든다. 간격과 한 번 보내는 데 걸리는 시간의 합이
    // max.poll.interval.ms(기본 5분)를 넘으면 소비자가 그룹에서 빠졌다 다시 들어와 같은 메시지를 다시 처리한다.
    // 한 번 보내기는 토픽 정보를 모르면 max.block.ms(기본 1분)까지 멈춘 뒤 저장 확인을 10초까지 기다려,
    // 합은 1분 + 1분 + 10초 = 약 2분 10초다
    static final long TRANSIENT_SEND_MAX_INTERVAL_MILLIS = 60_000L;
    static final long TRANSIENT_SEND_MAX_ELAPSED_MILLIS = 30 * 60_000L;

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
                new WalletMessageFailureRecoverer(messageFailureUseCase, jsonMapper),
                walletBackOff(), transientSendFailureBackOff()));
        return factory;
    }

    // 테스트에서 규칙과 기록 실패 때의 동작을 Kafka 없이 확인할 수 있게 빈 메서드와 나눠 둔다
    static DefaultErrorHandler walletErrorHandler(
            ConsumerRecordRecoverer recoverer, BackOff backOff, BackOff transientSendFailureBackOff) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // 간격 규칙은 한 메시지가 처음 실패할 때 고르고, 같은 메시지가 같은 종류의 예외로 다시 실패하면 이어 쓴다.
        // null이면 기본 간격(backOff)을 쓴다
        errorHandler.setBackOffFunction((record, exception) ->
                isTransientSendFailure(exception) ? transientSendFailureBackOff : null);
        // 30분 동안 조용히 다시 시도하면 결과가 나가지 않는 줄 모르므로 시도마다 남긴다. 끝내 실패하면 실패 기록이 ERROR로 남는다.
        // 원인 문구만 남긴다. 30분 동안 30번 넘게 남으므로 스택까지 찍으면 로그가 너무 길어진다
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) -> {
            if (isTransientSendFailure(exception)) {
                log.warn("[WALLET_SEND_RETRY] 결과를 Kafka로 보내지 못해 같은 메시지를 다시 받아 보냅니다. "
                                + "topic={}, partition={}, offset={}, key={}, attempt={}, reason={}",
                        record.topic(), record.partition(), record.offset(), record.key(), deliveryAttempt,
                        NestedExceptionUtils.getMostSpecificCause(exception).toString());
            }
        });
        // 값이 틀렸다는 예외는 같은 메시지로 다시 해도 결과가 같아 바로 기록한다. JSON을 읽지 못한 오류는 기본으로 다시 시도하지 않는다
        // 다른 모듈이 보낸 값의 오류는 표준 예외를 던지기로 해서(exception.md) 입구 전용 예외를 따로 두지 않는다
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

    /**
     * 결과를 Kafka로 보내다 잠깐 실패했는지 본다. 브로커가 돌아오면 다시 보내 풀리는 실패다.
     * - Kafka가 다시 하면 될 수 있다고 표시한 오류({@link RetriableException}): 응답 없음, 파티션 리더 없음, 복제본 부족 등
     * - 저장 확인을 기다리다 시간이 다 된 경우({@link TimeoutException})
     * 지갑 보내기는 브로커가 준 원인을 담아 다른 예외로 감싸 올리므로 원인을 끝까지 따라간다.
     */
    static boolean isTransientSendFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RetriableException || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static ExponentialBackOff transientSendFailureBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_INTERVAL_MILLIS, MULTIPLIER);
        backOff.setMaxInterval(TRANSIENT_SEND_MAX_INTERVAL_MILLIS);
        // 간격의 합으로 센다. 시도마다 보내기에 걸린 시간(보통 최대 10초, 토픽 정보를 모르면 더 길게)이 더해져
        // 실제로는 30분보다 더 걸린다
        backOff.setMaxElapsedTime(TRANSIENT_SEND_MAX_ELAPSED_MILLIS);
        return backOff;
    }

    static ExponentialBackOffWithMaxRetries walletBackOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MILLIS);
        backOff.setMultiplier(MULTIPLIER);
        return backOff;
    }
}
