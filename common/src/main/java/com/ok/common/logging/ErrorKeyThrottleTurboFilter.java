package com.ok.common.logging;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.slf4j.Marker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 같은 에러 키가 창(기본 1분) 안에 상한을 넘으면 넘은 로그를 버리고, 창이 끝날 때 "N건 생략" 요약 한 건으로 대신한다.
 *
 * <p>같은 장애가 초당 수천 건씩 WARN 이상을 쏟아내면 실패 appender는 동기로 쓰므로 디스크와 요청 스레드가 먼저 막힌다.
 * TurboFilter는 이벤트를 만들기 전에 불리므로 버려지는 로그의 비용도 줄인다. 에러 키는 로거 이름과 예외 클래스이고,
 * 예외가 없으면 메시지 틀({@code {}} 치환 전)이다. 인자 끝에 예외를 넘긴 경우도 그 예외를 쓴다. 값이 달라지는 메시지로 키가 무한히 늘지 않게 하려는 것이다.
 * 키 개수도 {@code maxKeys}로 막는다. 가득 차면 새 키는 추적하지 않고 통과시켜 로그를 잃지 않는 쪽을 택한다.
 *
 * <p>요약은 같은 키가 창이 끝난 뒤 다시 오거나 주기 점검({@link #sweep})에서 발견될 때 남는다.
 * 그래서 요약이 창이 끝난 뒤 최대 {@value #SWEEP_PERIOD_SECONDS}초 늦을 수 있다.
 */
public class ErrorKeyThrottleTurboFilter extends TurboFilter {

    static final String SUMMARY_LOGGER = "com.ok.common.logging.ErrorThrottle";
    private static final long SWEEP_PERIOD_SECONDS = 5;
    private static final long FULL_SWEEP_INTERVAL_MILLIS = 1000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private int limit = 10;
    private int windowSeconds = 60;
    private int maxKeys = 1000;
    private LongSupplier clock = System::currentTimeMillis;
    private ScheduledExecutorService sweeper;
    private volatile long lastFullSweepAt;

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (!isStarted() || level == null || !level.isGreaterOrEqual(Level.WARN) || SUMMARY_LOGGER.equals(logger.getName())) {
            return FilterReply.NEUTRAL;
        }
        // isErrorEnabled() 같은 레벨 확인 호출도 이 필터를 지난다. 로그가 아니므로 세지 않는다
        if (format == null && params == null && t == null) {
            return FilterReply.NEUTRAL;
        }
        Throwable cause = t;
        if (cause == null && params != null && params.length > 0 && params[params.length - 1] instanceof Throwable last) {
            cause = last;
        }
        String key = logger.getName() + " | " + (cause != null ? cause.getClass().getName() : format);
        long now = clock.getAsLong();
        if (windows.size() >= maxKeys && !windows.containsKey(key)) {
            // 가득 찬 동안 새 키마다 전체를 훑지 않도록 점검 간격을 둔다
            if (now - lastFullSweepAt >= FULL_SWEEP_INTERVAL_MILLIS) {
                lastFullSweepAt = now;
                sweep();
            }
            if (windows.size() >= maxKeys) {
                return FilterReply.NEUTRAL;
            }
        }
        int expiredSuppressed;
        boolean deny;
        while (true) {
            Window window = windows.computeIfAbsent(key, k -> new Window(now));
            synchronized (window) {
                // sweep이 이 창을 맵에서 지운 뒤라면 새 창으로 다시 센다. 떨어진 창에 쌓으면 요약에서 빠진다
                if (window.removed) {
                    continue;
                }
                expiredSuppressed = window.roll(now, windowMillis());
                window.count++;
                deny = window.count > limit;
                if (deny) {
                    window.suppressed++;
                }
                break;
            }
        }
        if (expiredSuppressed > 0) {
            logSummary(key, expiredSuppressed);
        }
        if (deny) {
            LogDropCounter.increment(LogDropCounter.Reason.THROTTLED, 1);
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }

    /** 창이 끝난 키를 정리하고, 생략한 건이 있으면 요약을 남긴다. */
    void sweep() {
        long now = clock.getAsLong();
        for (Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Window> entry = it.next();
            Window window = entry.getValue();
            int expiredSuppressed;
            synchronized (window) {
                if (now - window.start < windowMillis()) {
                    continue;
                }
                expiredSuppressed = window.roll(now, windowMillis());
                window.removed = true;
                it.remove();
            }
            if (expiredSuppressed > 0) {
                logSummary(entry.getKey(), expiredSuppressed);
            }
        }
    }

    private void logSummary(String key, int suppressed) {
        ((LoggerContext) getContext()).getLogger(SUMMARY_LOGGER)
                .warn("같은 에러 {}건 생략 (최근 {}초 동안 상한 {}회 초과). key={}", suppressed, windowSeconds, limit, key);
    }

    private long windowMillis() {
        return TimeUnit.SECONDS.toMillis(windowSeconds);
    }

    @Override
    public void start() {
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "error-throttle-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, SWEEP_PERIOD_SECONDS, SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);
        super.start();
    }

    @Override
    public void stop() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        super.stop();
    }

    /** 창 안에서 같은 키를 통과시킬 최대 횟수. 넘으면 버린다. */
    public void setLimit(int limit) {
        this.limit = limit;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public void setMaxKeys(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    private static final class Window {

        long start;
        int count;
        int suppressed;
        boolean removed;

        Window(long start) {
            this.start = start;
        }

        /** 창이 끝났으면 새 창으로 넘기고, 끝난 창에서 생략한 건수를 돌려준다. 아니면 0이다. */
        int roll(long now, long windowMillis) {
            if (now - start < windowMillis) {
                return 0;
            }
            int expiredSuppressed = suppressed;
            start = now;
            count = 0;
            suppressed = 0;
            return expiredSuppressed;
        }
    }
}
