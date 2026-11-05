package com.ok.common.config;

import com.ok.common.config.AsyncProperties.ExecutorProperties;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * {@code app.async.executors.<이름>} 설정으로 실행기를 만드는 템플릿
 *
 * <p>모듈은 자기가 쓸 실행기를 {@code @Bean} 한 줄로 선언한다. 설정을 읽고 옵션을 채우는 로직은 여기 한 곳에만 둔다.
 * <pre>
 * &#64;Bean(EXTERNAL_API_EXECUTOR)
 * AsyncTaskExecutor orderExternalApiExecutor(AsyncExecutorFactory factory) {
 *     return factory.create(EXTERNAL_API_EXECUTOR);
 * }
 * </pre>
 *
 * <p>설정의 {@code type}에 따라 두 가지를 만든다.
 * <ul>
 *   <li>{@code virtual}: 작업마다 가상 스레드를 새로 만든다. 대기열은 없고 {@code concurrencyLimit}으로 동시 실행 수만 막는다.
 *       상한이 차면 작업을 넘기는 스레드가 기다린다.</li>
 *   <li>{@code platform}: 정해진 수의 일반 스레드와 대기열을 쓴다. 대기열이 차기 전에는 작업을 넘기는 스레드가 기다리지 않는다.</li>
 * </ul>
 * 두 종류 모두 가득 찼을 때 작업을 거절하거나 버리지 않고 넘기는 스레드가 기다린다. 이벤트 리스너는 트랜잭션 커밋 뒤 콜백에서 작업을 넘기는데,
 * 거절하면 이미 커밋된 뒤에 호출한 쪽으로 예외가 올라가고 같은 커밋의 다른 콜백이 실행되지 않기 때문이다.
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
     * {@code platform}은 스프링이 빈으로 초기화·종료해 주므로, 빈으로 등록해서 쓴다.
     *
     * @throws IllegalStateException 설정에 없는 이름이면. 앱 시작 때 실패시켜, 운영에서 처음 쓸 때 늦게 발견되는 일을 막는다.
     */
    public AsyncTaskExecutor create(String name) {
        ExecutorProperties properties = executors.get(name);
        if (properties == null) {
            throw new IllegalStateException(
                    "app.async.executors." + name + " 설정이 없습니다. application yml에 추가하세요. 설정된 실행기: " + executors.keySet());
        }
        String threadNamePrefix = properties.threadNamePrefix() != null ? properties.threadNamePrefix() : name + "-";
        return switch (properties.type()) {
            case VIRTUAL -> createVirtual(properties, threadNamePrefix);
            case PLATFORM -> createPlatform(properties, threadNamePrefix);
        };
    }

    private SimpleAsyncTaskExecutor createVirtual(ExecutorProperties properties, String threadNamePrefix) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(properties.concurrencyLimit());
        executor.setTaskTerminationTimeout(properties.taskTerminationTimeout().toMillis());
        executor.setTaskDecorator(taskDecorator);
        return executor;
    }

    private ThreadPoolTaskExecutor createPlatform(ExecutorProperties properties, String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 대기열이 가득 차야 스레드가 코어를 넘어 늘어나므로, 대기열이 있는 풀에서 최대 크기는 의미가 없어 코어와 같게 둔다
        executor.setCorePoolSize(properties.poolSize());
        executor.setMaxPoolSize(properties.poolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setTaskDecorator(taskDecorator);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(properties.taskTerminationTimeout().toMillis());
        executor.setRejectedExecutionHandler(new WaitWhenFullHandler());
        return executor;
    }

    /**
     * 대기열이 가득 차면 거절하지 않고, 작업을 넘긴 스레드가 대기열에 자리가 날 때까지 기다렸다가 넣는다.
     * 거절 예외를 던지면 커밋 뒤 콜백에서 호출한 쪽으로 예외가 전달되고 같은 커밋의 다른 콜백이 실행되지 않는다.
     */
    static class WaitWhenFullHandler implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("실행기가 종료 중이라 작업을 받을 수 없습니다.");
            }
            try {
                executor.getQueue().put(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("대기열에 자리가 나기를 기다리다 중단되었습니다.", e);
            }
        }
    }
}
