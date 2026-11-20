package com.ok.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 비동기 실행기의 설정값. 실행기는 이름을 붙여 여러 개 둘 수 있다.
 *
 * <p>application.yaml의 {@code app.async.*} 값이 이 record에 바인딩된다. 기본값과 설명은 common-async.yaml에 있다.
 * <pre>
 * app:
 *   async:
 *     default-executor: event
 *     executors:
 *       event:
 *         type: platform
 *         pool-size: 8
 *         queue-capacity: 1000
 *       order-external-api:
 *         type: virtual
 *         concurrency-limit: 3
 * </pre>
 *
 * <p>{@code executors}를 List가 아닌 Map(이름: 설정)으로 둔 이유: 모듈 yml이나 환경 변수로 덮어쓸 때 List는 통째로 바뀌고
 * 순서 번호로 지정해야 하지만, Map은 같은 이름의 항목만 필드 단위로 바뀐다.
 *
 * <p>값이 잘못되면 기동 시점에 바로 실패하도록 생성자에서 검증한다.
 * {@code @DefaultValue}를 두어 yaml을 가져오지 않는 테스트나 모듈 단독 실행에서도 기동이 막히지 않게 한다.
 *
 * @param defaultExecutor 이름을 지정하지 않은 {@code @Async}({@code @ApplicationModuleListener} 포함)가 쓰는 실행기 이름.
 *                        {@code executors}에 없으면 기동이 실패한다.
 * @param executors       실행기 이름별 설정. {@value #EVENT_EXECUTOR}는 설정이 없어도 기본값으로 항상 있다.
 */
@ConfigurationProperties(prefix = "app.async")
public record AsyncProperties(
        @DefaultValue(EVENT_EXECUTOR) String defaultExecutor,
        Map<String, ExecutorProperties> executors
) {

    /** 이벤트 리스너가 쓰는 공통 실행기 이름 */
    public static final String EVENT_EXECUTOR = "event";

    public AsyncProperties {
        if (defaultExecutor == null || defaultExecutor.isBlank()) {
            throw new IllegalArgumentException("app.async.default-executor는 필수입니다.");
        }
        // event 실행기는 설정이 없어도 있어야 한다. yaml을 가져오지 않는 테스트·단독 실행도 기동되도록 기본값으로 채우고, 설정이 있으면 그 값이 이긴다
        Map<String, ExecutorProperties> merged = new HashMap<>();
        merged.put(EVENT_EXECUTOR, ExecutorProperties.eventDefaults());
        if (executors != null) {
            merged.putAll(executors);
        }
        executors = Map.copyOf(merged);
        if (!executors.containsKey(defaultExecutor)) {
            throw new IllegalArgumentException("app.async.executors." + defaultExecutor + " 설정이 없습니다. default-executor는 executors에 있는 이름이어야 합니다.");
        }
    }

    /** 실행기 종류 */
    public enum ExecutorType {
        /** 작업마다 가상 스레드를 만든다. 대기열이 없고, 상한({@code concurrencyLimit})을 넘으면 작업을 넘기는 스레드가 기다린다. */
        VIRTUAL,
        /** 정해진 수의 일반 스레드(풀)와 대기열을 쓴다. 대기열이 가득 차면 작업을 넘기는 스레드가 기다린다. */
        PLATFORM
    }

    /**
     * 실행기 하나의 설정값. {@code type}에 따라 쓰는 값이 다르고, 쓰지 않는 값은 무시한다.
     *
     * @param type                   실행기 종류. 요청 스레드가 막히면 안 되는 곳(이벤트 리스너 등)은 대기열이 있는 {@code platform}을 쓴다.
     * @param concurrencyLimit       {@code virtual}에서 동시에 실행할 작업 수의 상한. 작업마다 {@code REQUIRES_NEW} 트랜잭션으로 DB 커넥션을 하나씩 쓰면
     *                               {@code spring.datasource.hikari.maximum-pool-size}보다 작게 둔다.
     * @param poolSize               {@code platform}의 스레드 수. 대기열이 가득 차기 전에는 스레드가 늘지 않으므로 코어와 최대를 같게 쓴다.
     * @param queueCapacity          {@code platform}의 대기열 크기. 가득 차면 작업을 넘기는 스레드가 자리가 날 때까지 기다린다(거절하거나 버리지 않는다).
     * @param taskTerminationTimeout 앱을 종료할 때 실행 중인 작업이 끝나기를 기다리는 시간. "10s"처럼 Duration 형식으로 지정한다.
     * @param threadNamePrefix       스레드 이름의 앞부분. 비우면 "&lt;실행기 이름&gt;-"를 쓴다.
     */
    public record ExecutorProperties(
            @DefaultValue("virtual") ExecutorType type,
            @DefaultValue("5") int concurrencyLimit,
            @DefaultValue("8") int poolSize,
            @DefaultValue("1000") int queueCapacity,
            @DefaultValue("10s") Duration taskTerminationTimeout,
            String threadNamePrefix
    ) {

        public ExecutorProperties {
            if (type == null) {
                throw new IllegalArgumentException("type은 필수입니다. virtual 또는 platform 중 하나여야 합니다.");
            }
            if (concurrencyLimit < 1) {
                throw new IllegalArgumentException("concurrency-limit는 1 이상이어야 합니다. 입력값: " + concurrencyLimit);
            }
            if (poolSize < 1) {
                throw new IllegalArgumentException("pool-size는 1 이상이어야 합니다. 입력값: " + poolSize);
            }
            if (queueCapacity < 1) {
                throw new IllegalArgumentException("queue-capacity는 1 이상이어야 합니다. 입력값: " + queueCapacity);
            }
            if (taskTerminationTimeout == null || taskTerminationTimeout.isNegative()) {
                throw new IllegalArgumentException("task-termination-timeout은 0 이상이어야 합니다. 입력값: " + taskTerminationTimeout);
            }
            if (threadNamePrefix != null && threadNamePrefix.isBlank()) {
                throw new IllegalArgumentException("thread-name-prefix는 비우거나 공백이 아닌 값이어야 합니다.");
            }
        }

        // yaml이 없을 때 event 실행기의 기본 설정. 리스너가 요청 스레드를 막지 않도록 대기열이 있는 platform이다
        static ExecutorProperties eventDefaults() {
            return new ExecutorProperties(ExecutorType.PLATFORM, 5, 8, 1000, Duration.ofSeconds(10), null);
        }
    }
}
