package com.ok.product.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * product 모듈 전용 스케줄러. product의 {@code @Scheduled}는 모두 {@code scheduler = }{@link #SCHEDULER}로 이 스케줄러에서 돈다.
 *
 * <p>공용 기본 스케줄러는 스레드가 하나다. Jev를 부르는 작업자는 Jev가 응답하지 않으면 한 번에 최대 약 4분 20초(20건 × (연결 3초 + 응답 10초))
 * 스레드를 잡으므로, 같은 스레드를 쓰면 다른 모듈의 정기 작업까지 밀린다. 그래서 따로 두고, product 작업끼리도 막지 않게 스레드를 작업 수만큼 둔다(#788).
 * 빌더는 Boot가 기본 스케줄러에 쓰는 것이라 MDC 복사({@code MdcTaskDecorator})도 똑같이 붙는다.
 *
 * <p>다른 모듈이 이 스케줄러로 옮겨 가지 않는 이유는 common의 {@code SchedulingConfig}에 있다.
 */
@Configuration(proxyBeanMethods = false)
public class ProductSchedulingConfig {

    public static final String SCHEDULER = "productTaskScheduler";

    @Bean(SCHEDULER)
    public ThreadPoolTaskScheduler productTaskScheduler(ThreadPoolTaskSchedulerBuilder builder,
            @Value("${product.scheduling.pool-size}") int poolSize,
            @Value("${product.scheduling.thread-name-prefix}") String threadNamePrefix) {
        return builder.poolSize(poolSize)
                .threadNamePrefix(threadNamePrefix)
                // 종료할 때 실행 중인 작업을 기다리지 않고 끊는다. 꺼낸 요청은 임대(10분)가 끝나면 시도 횟수 그대로 다시 꺼내져 잃는 것이 없다.
                // 기다리면 Jev가 응답하지 않을 때 종료가 몇 분씩 늦어진다
                .awaitTermination(false)
                .build();
    }
}
