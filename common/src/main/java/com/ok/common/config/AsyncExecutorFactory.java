package com.ok.common.config;

import com.ok.common.config.AsyncProperties.ExecutorProperties;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * {@code app.async.executors.<이름>} 설정으로 가상 스레드 실행기를 만드는 템플릿
 *
 * <p>모듈은 자기가 쓸 실행기를 {@code @Bean} 한 줄로 선언한다. 설정을 읽고 가상 스레드 옵션을 채우는 로직은 여기 한 곳에만 둔다.
 * <pre>
 * &#64;Bean(EXTERNAL_API_EXECUTOR)
 * SimpleAsyncTaskExecutor orderExternalApiExecutor(AsyncExecutorFactory factory) {
 *     return factory.create(EXTERNAL_API_EXECUTOR);
 * }
 * </pre>
 *
 * <p>가상 스레드는 만드는 비용이 거의 없어 풀에 담아 재사용하지 않고 작업마다 새로 만든다.
 * 대신 부족한 자원인 DB 커넥션을 기준으로 {@code concurrencyLimit}으로 동시 실행 수를 막는다.
 * {@code virtualThreads}를 끄면 같은 방식으로 일반 스레드를 작업마다 만든다.
 *
 * <p>모든 실행기에 같은 {@link TaskDecorator}(MDC 전달)를 적용한다. 실행기가 늘어도 이 한 곳에서 함께 적용된다.
 */
public class AsyncExecutorFactory {

    private final Map<String, ExecutorProperties> executors;
    private final TaskDecorator taskDecorator;

    public AsyncExecutorFactory(AsyncProperties properties, TaskDecorator taskDecorator) {
        this.executors = properties.executors();
        this.taskDecorator = taskDecorator;
    }

    /**
     * 이름에 해당하는 설정으로 실행기를 만든다. 빈 이름은 호출하는 쪽이 이 이름과 같게 지정한다({@code @Async("이름")}으로 쓰기 위해서다).
     *
     * @throws IllegalStateException 설정에 없는 이름이면. 앱 시작 때 실패시켜, 운영에서 처음 쓸 때 늦게 발견되는 일을 막는다.
     */
    public SimpleAsyncTaskExecutor create(String name) {
        ExecutorProperties properties = executors.get(name);
        if (properties == null) {
            throw new IllegalStateException(
                    "app.async.executors." + name + " 설정이 없습니다. application yml에 추가하세요. 설정된 실행기: " + executors.keySet());
        }
        String threadNamePrefix = properties.threadNamePrefix() != null ? properties.threadNamePrefix() : name + "-";
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
        executor.setVirtualThreads(properties.virtualThreads());
        executor.setConcurrencyLimit(properties.concurrencyLimit());
        executor.setRejectTasksWhenLimitReached(properties.rejectTasksWhenLimitReached());
        executor.setTaskTerminationTimeout(properties.taskTerminationTimeout().toMillis());
        executor.setTaskDecorator(taskDecorator);
        return executor;
    }
}
