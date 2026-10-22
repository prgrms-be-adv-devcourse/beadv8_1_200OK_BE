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
     * 재처리 경로에서 역직렬화가 읽은 traceId를 보관한다. 이후 {@link TraceTaskDecorator}가 꺼내 쓴다.
     * null도 보관한다. "_trace 없이 저장된 이벤트"라는 표시이며, 보관하지 않으면 데코레이터가 제출 스레드의 traceId를
     * 복사해 저장값과 어긋난다. 매번 덮어쓰므로 앞선 이벤트의 값도 물려받지 않는다.
     */
    static void setPending(String traceId) {
        PENDING.set(new Pending(new PendingTrace(traceId), System.nanoTime() + PENDING_TTL_NANOS));
    }

    /**
     * 보관된 값을 꺼내며 비운다. 보관된 값이 없거나 만료됐으면 null을 반환한다.
     * 역직렬화만 하고 제출하지 않은 값이 나중 작업에 붙지 않도록 만료 시간을 둔다.
     */
    static PendingTrace takePending() {
        Pending pending = PENDING.get();
        PENDING.remove();
        if (pending == null || System.nanoTime() > pending.expiresAtNanos()) {
            return null;
        }
        return pending.trace();
    }

    static void clearPending() {
        PENDING.remove();
    }

    /**
     * 역직렬화가 읽은 결과. traceId가 null이면 저장된 이벤트에 _trace가 없었다는 뜻이다.
     */
    record PendingTrace(String traceId) {
    }

    private record Pending(PendingTrace trace, long expiresAtNanos) {
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
