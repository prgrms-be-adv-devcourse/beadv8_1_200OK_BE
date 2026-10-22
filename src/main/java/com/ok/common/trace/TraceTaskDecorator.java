package com.ok.common.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.HashMap;
import java.util.Map;

/**
 * 비동기 작업을 제출하는 스레드의 MDC를 작업 실행 스레드로 옮긴다.
 */
public class TraceTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        // 실행 시점에는 이미 다른 스레드라 MDC가 비어 있으므로, 제출 시점(제출 스레드)에 캡처한다
        Map<String, String> context = captureContext();

        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                applyContext(context);
                task.run();
            } finally {
                // 풀 스레드는 재사용된다. 되돌리지 않으면 다음 작업 로그에 이 작업의 traceId가 남는다
                applyContext(previous);
            }
        };
    }

    private Map<String, String> captureContext() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        Map<String, String> context = copy == null ? new HashMap<>() : new HashMap<>(copy);

        // 재처리 경로에서는 역직렬화가 원래 traceId를 남겨 두므로, 제출하는 스레드의 traceId보다 우선한다
        String pending = TraceContext.takePending();
        if (pending != null) {
            context.put(TraceContext.MDC_KEY, pending);
        }
        return context;
    }

    private void applyContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
