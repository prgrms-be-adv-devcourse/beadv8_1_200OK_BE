package com.ok.delivery.config;

import com.ok.common.config.AsyncExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * 배송 모듈이 쓰는 비동기 실행기를 등록한다. 값은 {@code app.async.executors.<이름>}에 있다.
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryAsyncConfig {

    /**
     * 커밋된 outbox 줄을 바로 Kafka로 보내는 실행기. 공통 {@code event} 실행기와 나눠, 브로커가 느려도 다른 리스너가 밀리지 않게 한다.
     */
    public static final String OUTBOX_SEND_EXECUTOR = "delivery-outbox-send";

    @Bean(OUTBOX_SEND_EXECUTOR)
    AsyncTaskExecutor deliveryOutboxSendExecutor(AsyncExecutorFactory factory) {
        return factory.create(OUTBOX_SEND_EXECUTOR);
    }
}
