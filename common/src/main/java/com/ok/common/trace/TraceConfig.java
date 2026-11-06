package com.ok.common.trace;

import org.springframework.batch.core.job.AbstractJob;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 추적(traceId 발급과 MDC 설정)에 필요한 빈을 등록한다. 진입점은 HTTP, {@code @Scheduled}, Spring Batch다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfig {

    /**
     * Spring Security 필터({@code SecurityProperties.DEFAULT_FILTER_ORDER} = -100)보다 앞에 둔다.
     * 뒤에 두면 인증 실패(401)와 접근 거부(403)가 Security 필터에서 끝나 이 필터를 거치지 않으므로,
     * 가장 추적이 필요한 거부 응답에 traceId가 없다.
     */
    static final int FILTER_ORDER = -200;

    @Bean
    public TraceIdGenerator traceIdGenerator(Clock clock, TraceProperties properties) {
        return new TraceIdGenerator(clock, properties.nodeId());
    }

    @Bean
    public FilterRegistrationBean<TraceFilter> traceFilterRegistration(TraceIdGenerator generator) {
        FilterRegistrationBean<TraceFilter> registration = new FilterRegistrationBean<>(new TraceFilter(generator));
        registration.setOrder(FILTER_ORDER);
        return registration;
    }

    // ObservationRegistry에 핸들러로 등록되어야 @Scheduled 실행 때 불린다. Boot가 ObservationHandler 빈을 모아 등록한다
    @Bean
    public ScheduledTraceObservationHandler scheduledTraceObservationHandler(TraceIdGenerator generator) {
        return new ScheduledTraceObservationHandler(generator);
    }

    /**
     * Spring Batch는 payout에만 있다. Batch가 없는 앱에서 이 설정이 Batch 타입을 읽으면 기동이 실패하므로,
     * Batch 타입은 클래스가 있을 때만 읽히는 이 중첩 설정에서만 쓴다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AbstractJob.class)
    static class BatchTraceConfig {

        @Bean
        static BeanPostProcessor batchTraceJobListenerRegistrar(ObjectProvider<TraceIdGenerator> generator) {
            return new BatchTraceJobListenerRegistrar(generator);
        }
    }
}
