package com.ok.order.config;

import com.ok.common.config.MdcTaskDecorator;
import com.ok.order.order.application.service.OrderOutboxRelayFacade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 주문 메시지 커밋 직후 발송({@link OrderOutboxRelayFacade#sendAsync}) 전용 실행기를 등록한다 (#715).
 *
 * <p>공용 실행기({@code AsyncExecutorFactory})는 대기열이 차면 넘기는 스레드가 기다린다. 발송을 afterCommit 콜백에서 시작하던 때는
 * 요청 스레드가 커넥션을 쥔 채 기다려, 대기열을 비울 스레드가 커넥션을 얻지 못하고 서로 기다렸다(2차 부하테스트).
 * 지금은 Facade가 커넥션을 돌려준 뒤 시작하지만(#795), 몰릴 때 요청 스레드가 발송 자리를 기다리지 않도록 차면 거절한다. 거절된 줄은 outbox에 PENDING으로 남아 30초 재발송이 보내므로 메시지는 잃지 않는다.
 *
 * <p>공용 실행기와 대기열을 나눠, 다른 비동기 작업이 몰려도 주문 메시지 발송이 함께 막히지 않게 한다.
 * 값은 yml 없이 기본값으로 동작해 monolith 설정을 고치지 않아도 된다. 바꿀 때만 {@code order.outbox.relay.*}로 덮어쓴다.
 */
@Configuration(proxyBeanMethods = false)
public class OrderOutboxRelayExecutorConfig {

    // 앱 종료 때 남은 발송을 기다리는 시간. 발송 저장 확인 상한(10초)과 같게 둔다. 못 보낸 줄은 PENDING으로 남아 재시작 뒤 재발송이 보낸다
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(10);

    // 발송 하나는 짧은 트랜잭션 두 번(읽기, 보냄 기록)에만 커넥션을 쓴다.
    // 스레드를 적게 두어 HTTP 요청과 커넥션(기본 10개)을 다투지 않게 하고, 평소 주문 수에는 2개로 충분하다
    @Bean(OrderOutboxRelayFacade.RELAY_EXECUTOR)
    public ThreadPoolTaskExecutor orderOutboxRelayExecutor(
            MdcTaskDecorator mdcTaskDecorator,
            @Value("${order.outbox.relay.pool-size:2}") int poolSize,
            @Value("${order.outbox.relay.queue-capacity:1000}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 대기열이 있는 풀은 대기열이 차야 코어를 넘어 늘어나므로, 최대 크기를 코어와 같게 둔다(공용 실행기와 같음)
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("order-outbox-relay-");
        // 추적 키(traceId 등)를 발송 스레드로 넘긴다. 공용 실행기와 같은 데코레이터다
        executor.setTaskDecorator(mdcTaskDecorator);
        // 차면 기다리지 않고 거절한다. 거절은 발송을 시작한 Facade가 잡아 재발송에 맡긴다(OrderFacade)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(TERMINATION_TIMEOUT.toMillis());
        return executor;
    }
}
