package com.ok.common.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * 추적(traceId 발급과 MDC 설정)에 필요한 빈을 등록한다. 진입점은 HTTP, {@code @Scheduled}, Spring Batch다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfig {

    private static final Logger log = LoggerFactory.getLogger(TraceConfig.class);

    /**
     * Spring Security 필터({@code SecurityProperties.DEFAULT_FILTER_ORDER} = -100)보다 앞에 둔다.
     * 뒤에 두면 인증 실패(401)와 접근 거부(403)가 Security 필터에서 끝나 이 필터를 거치지 않으므로,
     * 가장 추적이 필요한 거부 응답에 traceId가 없다.
     * {@link TraceContext#open}이 MDC를 모두 비우므로, 이 필터보다 앞서 MDC에 값을 넣는 필터가 생기면 그 값은 지워진다.
     */
    static final int FILTER_ORDER = -200;

    @Bean
    public TraceIdGenerator traceIdGenerator(Clock clock, TraceProperties properties, Environment environment) {
        // 기본값 0으로 서버를 늘리면 오류 없이 서로 다른 서버의 traceId가 겹칠 수 있어, 설정하지 않았다는 사실을 기동 로그에 남긴다
        if (environment.containsProperty("trace.node-id")) {
            log.info("trace.node-id={}", properties.nodeId());
        } else {
            log.warn("trace.node-id가 설정되지 않아 기본값 {}을 쓴다. 서버가 둘 이상이면 서버마다 다르게 지정해야 traceId가 겹치지 않는다.", properties.nodeId());
        }
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
