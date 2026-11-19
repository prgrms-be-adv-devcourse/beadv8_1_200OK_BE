package com.ok.delivery.config;

import com.ok.common.config.MdcTaskDecorator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 배송 모듈이 쓰는 비동기 실행기를 등록한다. 값은 {@code delivery.outbox.send.*}에 있다 (#793).
 *
 * <p>공용 실행기({@code AsyncExecutorFactory})를 쓰지 않는 이유: 공용 실행기는 대기열이 차면 넘기는 스레드가 기다린다.
 * 이 실행기에 작업을 넘기는 곳은 커밋 뒤 콜백이라 DB 커넥션을 돌려주기 전이다. 요청 스레드가 커넥션을 쥔 채 기다리고,
 * 대기열을 비울 발송 스레드는 줄 읽기·"보냄" 기록에 커넥션이 필요해 서로 기다리다 커넥션 풀이 막힐 수 있다(주문 #715와 같은 문제).
 * 그래서 차면 기다리지 않고 버린다. 버린 줄은 outbox에 PENDING으로 남아 발송 작업({@code DeliveryOutboxRelayScheduler})이 보낸다.
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
    public static final String DROP_REASON_REJECTED = "rejected";
    public static final String DROP_REASON_STALE = "stale";

    // 앱 종료 때 남은 발송을 기다리는 시간. 공용 실행기 기본값과 같고 브로커 확인 대기 상한(10초)과 같다. 못 보낸 줄은 PENDING으로 남아 재시작 뒤 발송 작업이 보낸다
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(10);

    // 발송 하나는 짧은 트랜잭션 두 번(줄 읽기, "보냄" 기록)에만 커넥션을 쓰고 브로커 확인 중에는 잡지 않는다. 확인이 수 ms라 2개로도 초당 수백 건을 보낸다.
    // 대기열 크기는 #793 부하테스트로 정한다. 그때까지는 공용 실행기를 쓰던 때의 값(2, 1000)을 그대로 둔다
    @Bean(OUTBOX_SEND_EXECUTOR)
    public ThreadPoolTaskExecutor deliveryOutboxSendExecutor(
            MdcTaskDecorator mdcTaskDecorator,
            MeterRegistry meterRegistry,
            @Value("${delivery.outbox.send.pool-size:2}") int poolSize,
            @Value("${delivery.outbox.send.queue-capacity:1000}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 대기열이 있는 풀은 대기열이 차야 코어를 넘어 늘어나므로, 최대 크기를 코어와 같게 둔다(공용 실행기와 같음)
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("delivery-outbox-");
        // 추적 키(traceId 등)를 발송 스레드로 넘긴다. 공용 실행기와 같은 데코레이터다
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new DropAndCountPolicy(Counter.builder(OUTBOX_SEND_DROPPED_METRIC)
                .description("배송 outbox 즉시 발송을 하지 않고 발송 작업에 맡긴 건수")
                .tag("reason", DROP_REASON_REJECTED)
                .register(meterRegistry)));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(TERMINATION_TIMEOUT.toMillis());
        return executor;
    }

    /**
     * 받을 수 없는 작업을 예외 없이 버리고 건수만 센다.
     * 예외를 던지면 안 되는 이유: 작업을 넘기는 곳이 Spring이 부르는 커밋 뒤 콜백이라 잡을 곳이 없다. 예외가 올라가면 이미 커밋된 요청이 실패 응답이 되고
     * 같은 커밋의 다른 콜백이 실행되지 않는다. 로그를 남기지 않는 이유: 이 처리도 요청 스레드가 커넥션을 쥔 채 실행하므로, 몰릴 때 건마다 로그를 쓰면 그만큼 커넥션을 오래 잡는다.
     */
    static final class DropAndCountPolicy implements RejectedExecutionHandler {

        private final Counter dropped;

        DropAndCountPolicy(Counter dropped) {
            this.dropped = dropped;
        }

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            dropped.increment();
        }
    }
}
