package com.ok.common.trace;

import org.springframework.core.task.TaskDecorator;

/**
 * 스케줄러가 실행하는 작업마다 새 TraceID를 부여한다. 요청 없이 시작되는 흐름의 출발점이다.
 */
public class TraceScheduledTaskDecorator implements TaskDecorator {

    private final TraceIdGenerator traceIdGenerator;

    public TraceScheduledTaskDecorator(TraceIdGenerator traceIdGenerator) {
        this.traceIdGenerator = traceIdGenerator;
    }

    @Override
    public Runnable decorate(Runnable task) {
        // MDC를 복사하지 않고 실행할 때마다 새로 만든다. 크론은 실행 도중에 다음 실행을 예약하므로
        // 복사하면 첫 실행의 traceId가 이후 모든 실행에 재사용된다
        return () -> {
            try (TraceContext.Scope ignored = TraceContext.open(traceIdGenerator.next())) {
                task.run();
            }
        };
    }
}
