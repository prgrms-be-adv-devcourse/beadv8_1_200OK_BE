package com.ok.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

import com.ok.common.config.AsyncExecutorFactory;

/**
 * product 모듈 전용 비동기 실행기. 설정은 {@code app.async.executors.<이름>}에 있다({@link AsyncExecutorFactory} 참고).
 */
@Configuration(proxyBeanMethods = false)
public class ProductAsyncConfig {

    public static final String SEARCH_BACKFILL_EXECUTOR = "product-search-backfill";

    @Bean(SEARCH_BACKFILL_EXECUTOR)
    public AsyncTaskExecutor productSearchBackfillExecutor(AsyncExecutorFactory factory) {
        return factory.create(SEARCH_BACKFILL_EXECUTOR);
    }
}
