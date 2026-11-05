package com.ok.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * {@code @Async}와 {@code @ApplicationModuleListener}가 쓰는 실행기를 등록한다.
 *
 * <p>앱이 {@code @EnableAsync}를 켜지 않아도 Spring Modulith가 자동으로 켜 주므로({@code EventPublicationAutoConfiguration}),
 * {@code @ApplicationModuleListener}는 원래도 비동기로 실행된다. 다만 이름을 지정하지 않은 {@code @Async}는 모두
 * Boot의 기본 실행기({@code applicationTaskExecutor}: 코어 8, 대기열 무제한) 하나를 공유한다. 그러면 느린 작업이 다른 비동기 작업을 지연시키고,
 * 밀린 작업이 대기열에 메모리로 쌓인다. 이 설정은 그 공유 풀을 용도별 전용 실행기로 나누고 동시 실행 상한을 yml로 관리하게 한다.
 *
 * <p>가상 스레드 실행기에만 적용하려고 {@code spring.threads.virtual.enabled}(Tomcat 요청 스레드와 {@code @Scheduled}까지 바꾸는 전역 설정)는 쓰지 않는다.
 * 이 실행기를 직접 등록하면 Boot의 {@code applicationTaskExecutor} 자동 구성은 물러난다.
 * 그래서 {@code spring.task.execution.*} 설정은 적용되지 않고, 값은 {@link AsyncProperties}({@code app.async.*})로 받는다.
 *
 * <p>여기서는 공통 실행기({@value AsyncProperties#EVENT_EXECUTOR})만 만든다. 모듈이 쓸 실행기는 각 모듈이
 * {@link AsyncExecutorFactory}로 한 줄씩 선언한다.
 */
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties(AsyncProperties.class)
public class AsyncConfig {

    // 호출한 스레드의 MDC를 비동기 작업의 스레드로 복사하도록 모든 실행기에 적용한다.
    // MDC에 값을 넣는 쪽(TraceID 필터 등)은 아직 없어서, 지금은 복사할 값이 없다
    @Bean
    public MdcTaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    @Bean
    public AsyncExecutorFactory asyncExecutorFactory(AsyncProperties properties, MdcTaskDecorator mdcTaskDecorator) {
        return new AsyncExecutorFactory(properties, mdcTaskDecorator);
    }

    // 이벤트 리스너용 공통 실행기
    @Bean(AsyncProperties.EVENT_EXECUTOR)
    public SimpleAsyncTaskExecutor eventTaskExecutor(AsyncExecutorFactory factory) {
        return factory.create(AsyncProperties.EVENT_EXECUTOR);
    }

    // 이름 없는 @Async는 default-executor 이름의 빈으로 간다.
    // 타입(Executor)으로 찾으면 @EnableScheduling이 만드는 taskScheduler도 Executor라 후보가 둘이 되어 앱이 시작되지 않으므로 이름으로 찾는다.
    // 빈을 만들 때 바로 꺼내 쓰므로 그 이름의 빈이 없으면 시작할 때 실패한다
    @Bean
    public AsyncConfigurer asyncConfigurer(AsyncProperties properties, BeanFactory beanFactory) {
        Executor defaultExecutor = beanFactory.getBean(properties.defaultExecutor(), Executor.class);
        return new AsyncConfigurer() {
            @Override
            public Executor getAsyncExecutor() {
                return defaultExecutor;
            }

            // 비동기 리스너의 예외는 호출한 쪽으로 전달되지 않는다. 이벤트 기록은 Modulith가 미완료로 남기므로 원인을 찾을 수 있게 로그를 남긴다.
            // 이벤트에는 수령인 이름·전화번호·주소 같은 개인정보가 담길 수 있어 파라미터 값은 남기지 않고 타입 이름만 남긴다
            @Override
            public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                return (throwable, method, params) -> log.error(
                        "[ASYNC_LISTENER_FAILED] 비동기 처리에 실패했습니다. method={}.{}, paramTypes={}",
                        method.getDeclaringClass().getSimpleName(), method.getName(), paramTypeNames(params), throwable);
            }
        };
    }

    private static List<String> paramTypeNames(Object[] params) {
        return Arrays.stream(params)
                .map(param -> param == null ? "null" : param.getClass().getSimpleName())
                .toList();
    }
}
