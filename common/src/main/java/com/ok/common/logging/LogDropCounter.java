package com.ok.common.logging;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 로그 폭증 방지 장치가 버린 로그 건수를 사유별로 센다.
 *
 * <p>logback은 Spring 컨텍스트보다 먼저 만들어져 appender와 필터가 빈을 주입받을 수 없다. 그래서 정적 카운터에 쌓고
 * {@link LoggingConfig}가 이 값을 Micrometer 지표로 노출한다.
 */
public final class LogDropCounter {

    /** 버려진 사유. 지표의 {@code reason} 태그 값이 된다. */
    public enum Reason {
        /** 비동기 큐가 가득 차 버렸다. */
        QUEUE_FULL,
        /** 같은 에러 키가 상한을 넘어 요약으로 대체했다. */
        THROTTLED,
        /** HTTP 성공 로그 샘플링에서 제외했다. */
        SAMPLED
    }

    private static final Map<Reason, AtomicLong> COUNTS = new EnumMap<>(Reason.class);

    static {
        for (Reason reason : Reason.values()) {
            COUNTS.put(reason, new AtomicLong());
        }
    }

    private LogDropCounter() {
    }

    public static void increment(Reason reason, long amount) {
        COUNTS.get(reason).addAndGet(amount);
    }

    public static long count(Reason reason) {
        return COUNTS.get(reason).get();
    }

    /** 테스트 사이에 값을 비우기 위한 것이다. */
    static void reset() {
        COUNTS.values().forEach(count -> count.set(0));
    }
}
