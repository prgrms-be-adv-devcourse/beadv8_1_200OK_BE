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

    // 역직렬화 직후 같은 스레드에서 리스너 작업이 제출될 때까지만 의미가 있다. 그 사이 시간 상한
    private static final long PENDING_TTL_NANOS = 1_000_000_000L;
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

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

    /**
     * 재처리 경로에서 역직렬화가 읽은 원래 traceId를 보관한다. 이후 {@link TraceTaskDecorator}가 꺼내 쓴다.
     * null이면 이전에 남은 값을 지운다(앞선 이벤트의 traceId를 물려받지 않도록).
     */
    static void setPending(String traceId) {
        if (traceId == null) {
            PENDING.remove();
            return;
        }
        PENDING.set(new Pending(traceId, System.nanoTime() + PENDING_TTL_NANOS));
    }

    /**
     * 보관된 값을 꺼내며 비운다. 역직렬화만 하고 제출하지 않은 값이 나중 작업에 붙지 않도록 만료 시간을 둔다.
     */
    static String takePending() {
        Pending pending = PENDING.get();
        PENDING.remove();
        if (pending == null || System.nanoTime() > pending.expiresAtNanos()) {
            return null;
        }
        return pending.traceId();
    }

    static void clearPending() {
        PENDING.remove();
    }

    private record Pending(String traceId, long expiresAtNanos) {
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
