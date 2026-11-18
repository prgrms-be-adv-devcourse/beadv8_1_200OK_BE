package com.ok.product.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.ok.common.config.AsyncExecutorFactory;
import com.ok.common.config.MdcTaskDecorator;

/**
 * product 모듈 전용 비동기 실행기. 설정은 {@code app.async.executors.<이름>}에 있다({@link AsyncExecutorFactory} 참고).
 * 검색어 로그 실행기만 예외로 {@code product.search.log.*}에 있다.
 */
@Configuration(proxyBeanMethods = false)
public class ProductAsyncConfig {

    public static final String SEARCH_BACKFILL_EXECUTOR = "product-search-backfill";
    public static final String SEARCH_LOG_EXECUTOR = "product-search-log";

    @Bean(SEARCH_BACKFILL_EXECUTOR)
    public AsyncTaskExecutor productSearchBackfillExecutor(AsyncExecutorFactory factory) {
        return factory.create(SEARCH_BACKFILL_EXECUTOR);
    }

    // 공통 팩토리의 실행기는 가득 차면 넘기는 스레드가 기다려 구매자 검색이 로그 저장에 묶인다. 그래서 거절(기본 정책)하고 저장하는 쪽이 버린다
    @Bean(SEARCH_LOG_EXECUTOR)
    public ThreadPoolTaskExecutor productSearchLogExecutor(MdcTaskDecorator mdcTaskDecorator,
            @Value("${product.search.log.pool-size}") int poolSize,
            @Value("${product.search.log.queue-capacity}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(SEARCH_LOG_EXECUTOR + "-");
        executor.setTaskDecorator(mdcTaskDecorator);
        // 종료할 때 대기열에 남은 로그를 잠깐 저장하고 끝낸다. 오래 붙잡지 않도록 짧게 둔다
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(2);
        return executor;
    }
}
