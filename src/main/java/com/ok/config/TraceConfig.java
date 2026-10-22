package com.ok.config;

import com.ok.common.trace.TraceFilter;
import com.ok.common.trace.TraceIdGenerator;
import com.ok.common.trace.TraceTaskDecorator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

/**
 * TraceID 생성기, HTTP 필터, 비동기 작업용 {@link TaskDecorator}를 등록한다.
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
    // 스케줄러에도 같이 붙는데, 실행마다 새 ID를 주는 스케줄러용 처리는 #281에서 커스터마이저로 대체한다.
    @Bean
    public TaskDecorator traceTaskDecorator() {
        return new TraceTaskDecorator();
    }
}
