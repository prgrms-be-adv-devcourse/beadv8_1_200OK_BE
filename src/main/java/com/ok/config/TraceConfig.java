package com.ok.config;

import com.ok.common.trace.TraceFilter;
import com.ok.common.trace.TraceIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * TraceID 생성기와 HTTP 필터를 등록한다.
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
}
