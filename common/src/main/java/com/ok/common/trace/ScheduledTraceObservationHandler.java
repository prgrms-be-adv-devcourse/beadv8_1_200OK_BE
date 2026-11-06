package com.ok.common.trace;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * {@code @Scheduled} 메서드가 실행되는 동안 새 traceId를 MDC에 둔다.
 *
 * <p>Spring은 스케줄 작업을 실행할 때 관찰(Observation)을 열고, 작업을 실행하는 스레드에서 범위를 열고 닫는다.
 * 그 시점에 끼어들어 실행마다 새 traceId를 발급하고, 끝나면 이전 MDC로 되돌린다.
 * 범위가 열린 스레드에서 닫히므로 MDC(스레드별)를 여닫는 짝이 맞는다.
 */
public class ScheduledTraceObservationHandler implements ObservationHandler<ScheduledTaskObservationContext> {

    private final TraceIdGenerator generator;

    public ScheduledTraceObservationHandler(TraceIdGenerator generator) {
        this.generator = generator;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }

    @Override
    public void onScopeOpened(ScheduledTaskObservationContext context) {
        context.put(TraceContext.Scope.class, TraceContext.open(generator.next()));
    }

    @Override
    public void onScopeClosed(ScheduledTaskObservationContext context) {
        Object scope = context.remove(TraceContext.Scope.class);
        if (scope instanceof TraceContext.Scope opened) {
            opened.close();
        }
    }
}
