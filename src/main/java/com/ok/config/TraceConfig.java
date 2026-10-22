package com.ok.config;

import com.ok.common.trace.TraceFilter;
import com.ok.common.trace.TraceIdGenerator;
import com.ok.common.trace.TraceScheduledTaskDecorator;
import com.ok.common.trace.TraceTaskDecorator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

/**
 * TraceID 생성기, HTTP 필터, 비동기 작업용 {@link TaskDecorator}, 스케줄러 작업용 커스터마이저를 등록한다.
 */
@Configuration(proxyBeanMethods = false)
public class TraceConfig {

    // 노드 ID는 인스턴스마다 달라야 같은 ms에 만든 ID가 겹치지 않는다
    @Bean
    public TraceIdGenerator traceIdGenerator(@Value("${trace.node-id:0}") long nodeId) {
        return new TraceIdGenerator(nodeId);
    }

    @Bean
    public FilterRegistrationBean<TraceFilter> traceFilterRegistration(TraceIdGenerator traceIdGenerator) {
        FilterRegistrationBean<TraceFilter> registration = new FilterRegistrationBean<>(new TraceFilter(traceIdGenerator));
        // 보안 필터 등 뒤따르는 필터의 로그에도 traceId가 찍히도록 가장 먼저 실행한다
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    // Boot가 TaskDecorator 빈을 applicationTaskExecutor에 붙이고, Modulith 리스너(@Async)가 그 실행기를 쓴다.
    // 스케줄러에도 같이 붙지만, 아래 traceSchedulerCustomizer가 스케줄러용으로 대체한다.
    @Bean
    public TaskDecorator traceTaskDecorator() {
        return new TraceTaskDecorator();
    }

    // TaskDecorator 빈으로 등록하지 않는다. Boot가 TaskDecorator 빈을 @Async 실행기에도 붙이므로
    // 모든 리스너 실행마다 새 traceId를 만들어 발행 쪽 traceId를 끊어 버린다.
    // Boot가 스케줄러에 붙여 둔 복사형 데코레이터(traceTaskDecorator)는 이 커스터마이저가 대체한다.
    // spring.threads.virtual.enabled를 켜면 Boot가 SimpleAsyncTaskScheduler를 만들어 이 커스터마이저가 적용되지 않으므로
    // SimpleAsyncTaskSchedulerCustomizer도 추가해야 한다.
    @Bean
    public ThreadPoolTaskSchedulerCustomizer traceSchedulerCustomizer(TraceIdGenerator traceIdGenerator) {
        return scheduler -> scheduler.setTaskDecorator(new TraceScheduledTaskDecorator(traceIdGenerator));
    }
}
