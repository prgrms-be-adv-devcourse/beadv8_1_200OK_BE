package com.ok.delivery.config;

import com.ok.common.config.MdcTaskDecorator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;

/**
 * 배송 모듈이 쓰는 비동기 실행기를 등록한다. 값은 {@code delivery.outbox.send.*}에 있다 (#793).
 *
 * <p>공용 실행기({@code AsyncExecutorFactory})를 쓰지 않는 이유: 공용 실행기는 대기열이 차면 넘기는 스레드가 기다린다.
 * 대기열은 짧은 몰림을 흡수할 뿐이라, 차면 기다리지 않고 거절({@code TaskRejectedException})한다. 넘기는 {@code ShipmentFacade}가 잡아 세고 넘어가며,
 * 거절한 줄은 outbox에 PENDING으로 남아 발송 작업({@code DeliveryOutboxRelayScheduler})이 보낸다.
 *
 * <p>대기열 길이는 Spring Boot가 {@code ThreadPoolTaskExecutor} 빈마다 붙이는 {@code executor.queued{name="delivery-outbox-send"}}로 본다.
 * 버린 건수는 {@value #OUTBOX_SEND_DROPPED_METRIC}로 센다.
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryAsyncConfig {

    /**
     * 커밋된 outbox 줄을 바로 Kafka로 보내는 실행기. 공통 {@code event} 실행기와 나눠, 브로커가 느려도 다른 리스너가 밀리지 않게 한다.
     */
    public static final String OUTBOX_SEND_EXECUTOR = "delivery-outbox-send";

    /**
     * 즉시 발송을 하지 않고 발송 작업에 맡긴 건수. {@code reason}이 {@value #DROP_REASON_REJECTED}면 대기열이 가득 찼거나 종료 중이라 버린 것,
     * {@value #DROP_REASON_STALE}이면 대기열에서 {@code min-age}보다 오래 기다려 보내지 않은 것이다.
     */
    public static final String OUTBOX_SEND_DROPPED_METRIC = "delivery.outbox.send.dropped";
    public static final String OUTBOX_SEND_DROPPED_DESCRIPTION = "배송 outbox 즉시 발송을 하지 않고 발송 작업에 맡긴 건수";
    public static final String DROP_REASON_REJECTED = "rejected";
    public static final String DROP_REASON_STALE = "stale";

    // 앱 종료 때 남은 발송을 기다리는 시간. 공용 실행기 기본값과 같고 브로커 확인 대기 상한(10초)과 같다. 못 보낸 줄은 PENDING으로 남아 재시작 뒤 발송 작업이 보낸다
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(10);

    // 발송 하나는 짧은 트랜잭션 두 번(줄 읽기, "보냄" 기록)에만 커넥션을 쓰고 브로커 확인 중에는 잡지 않는다. 스레드 4개, 대기열 20은 #793 부하테스트로 정했다.
    // 대기열에서 기다리는 시간(대기열/처리량)이 relay min-age보다 짧아야 오래 기다려 버리는 작업이 없다 (docs/decisions/delivery/793-outbox-send-drop-when-full.md)
    @Bean(OUTBOX_SEND_EXECUTOR)
    public ThreadPoolTaskExecutor deliveryOutboxSendExecutor(
            MdcTaskDecorator mdcTaskDecorator,
            @Value("${delivery.outbox.send.pool-size:4}") int poolSize,
            @Value("${delivery.outbox.send.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 대기열이 있는 풀은 대기열이 차야 코어를 넘어 늘어나므로, 최대 크기를 코어와 같게 둔다(공용 실행기와 같음)
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("delivery-outbox-");
        // 추적 키(traceId 등)를 발송 스레드로 넘긴다. 공용 실행기와 같은 데코레이터다
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(TERMINATION_TIMEOUT.toMillis());
        return executor;
    }
}
