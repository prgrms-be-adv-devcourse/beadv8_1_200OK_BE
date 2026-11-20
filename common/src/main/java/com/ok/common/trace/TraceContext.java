package com.ok.common.trace;

import org.slf4j.MDC;

import java.util.Map;

/**
 * 추적 키를 MDC에 넣고 빼는 도우미. 로그에 찍히는 값은 모두 MDC를 거친다.
 *
 * <p>상태가 MDC(스레드 전역)에 있고 필터·스케줄러 핸들러·배치 리스너가 모두 같은 MDC를 쓰므로, 주입받을 빈 없이 정적 메서드로 둔다.
 *
 * <p>진입점은 {@link #open(String)}으로 범위를 열고 {@link Scope#close()}로 닫는다.
 * 닫으면 범위에 들어오기 전의 MDC 전체로 되돌린다. 스레드를 재사용하는 곳에서 값이 다음 작업으로 새지 않고,
 * {@code MdcTaskDecorator}가 작업이 끝난 뒤 자기 MDC로 되돌리는 동작과도 겹치지 않는다.
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String ORDER_ID = "orderId";
    public static final String SHIPMENT_ID = "shipmentId";
    public static final String MODULE = "module";
    public static final String STEP = "step";
    public static final String EVENT_TYPE = "eventType";

    private TraceContext() {
    }

    /**
     * 새 추적 범위를 연다. 흐름이 새로 시작되는 곳이므로 이전 MDC를 비우고 traceId만 넣는다.
     * 이전 흐름의 orderId 같은 값이 새 흐름으로 이어지면 로그가 엉뚱한 주문에 묶이기 때문이다.
     */
    public static Scope open(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId는 필수입니다.");
        }
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put(TRACE_ID, traceId);
        return new Scope(previous);
    }

    /** 현재 스레드의 traceId. 추적 범위 밖이면 null이다. */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    /** 범위 안에서 주문 번호를 붙인다. 주문 생성처럼 번호를 나중에 알게 되는 곳에서 쓴다. */
    public static void bindOrderId(Long orderId) {
        bind(ORDER_ID, orderId);
    }

    /** 범위 안에서 배송 번호를 붙인다. */
    public static void bindShipmentId(Long shipmentId) {
        bind(SHIPMENT_ID, shipmentId);
    }

    // 로그 스키마의 orderId·shipmentId는 숫자지만 MDC는 문자열만 받는다
    private static void bind(String key, Long value) {
        if (value == null) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, String.valueOf(value));
    }

    /** {@link #open(String)}이 연 범위. 닫으면 진입 전 MDC로 되돌린다. */
    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                MDC.clear();
                return;
            }
            MDC.setContextMap(previous);
        }
    }
}
