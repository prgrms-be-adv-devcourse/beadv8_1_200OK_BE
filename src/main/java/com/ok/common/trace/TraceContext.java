package com.ok.common.trace;

import org.slf4j.MDC;

import java.util.Optional;

/**
 * 현재 스레드의 TraceID를 MDC에서 읽고, 범위를 정해 설정·복원한다.
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    // Kafka 전환 후에는 같은 이름을 레코드 헤더 키로 쓴다
    public static final String HEADER_NAME = "X-Trace-Id";

    private TraceContext() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    /**
     * traceId를 MDC에 설정하고, 닫을 때 이전 값으로 되돌린다.
     */
    public static Scope open(String traceId) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, traceId);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {

        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
