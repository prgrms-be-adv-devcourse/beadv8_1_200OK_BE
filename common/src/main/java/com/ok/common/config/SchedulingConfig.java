package com.ok.common.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.TaskSchedulerRouter;

/**
 * {@code @Scheduled}를 켜고, 스케줄러를 지정하지 않은 작업이 쓰는 기본 스케줄러({@code taskScheduler})를 등록한다.
 *
 * <p>Boot는 {@code TaskScheduler} 빈이 하나라도 있으면 기본 스케줄러를 만들지 않는다. 그래서 모듈이 전용 스케줄러(예: product)를
 * 등록하면 기본 스케줄러가 사라지고, Spring은 하나 남은 그 전용 스케줄러를 기본으로 골라 다른 모듈의 작업까지 그쪽으로 옮긴다.
 * 이를 막으려고 Boot가 쓰는 빌더(설정 {@code spring.task.scheduling.*}, {@link MdcTaskDecorator} 포함)로 같은 이름의 빈을 직접 만든다.
 * 스케줄러가 여럿이면 Spring은 이 이름의 빈을 기본으로 고른다(#788).
 *
 * <p>Boot와 달리 가상 스레드({@code spring.threads.virtual.enabled})를 켜도 일반 스레드 풀로 남는다. 지금은 켠 앱이 없다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean(name = TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
