package com.ok.common.trace;

import java.time.Clock;
import java.time.Instant;

/**
 * Snowflake 방식으로 traceId를 발급한다. 반환값은 10진 long 문자열이다.
 *
 * <p>비트 구성은 시각(41) + nodeId(10) + 시퀀스(12)다. 시각은 {@link #EPOCH_MILLIS}(2026-01-01T00:00:00Z) 기준 경과 ms라서
 * 값만으로 발급 시각을 역산할 수 있고, 시각이 앞이라 나중에 발급한 값이 항상 더 크다.
 * 시각은 공용 {@link Clock}에서 읽는다. 새 Clock을 만들면 같은 타입의 빈이 둘이 되어 앱이 시작되지 않는다(ClockConfig 참고).
 *
 * <p>같은 ms에 시퀀스를 다 쓰면 다음 ms가 될 때까지 기다린다.
 * 시계가 뒤로 가면(NTP 보정 등) 예외를 던지지 않고 마지막 발급 시각을 유지한 채 시퀀스를 올려 진행한다.
 * traceId는 로그를 잇는 용도라 발급이 멈추는 것보다 잠시 시각이 어긋나는 쪽이 낫기 때문이다.
 * 이때 시퀀스까지 소진되면 시계가 따라올 때까지 기다리지 않고 시각을 1ms 앞으로 당겨 쓴다. 보정 폭이 크면 오래 막히기 때문이다.
 */
public class TraceIdGenerator {

    /** 시각의 기준(0)이 되는 시점 */
    public static final long EPOCH_MILLIS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    public static final int NODE_ID_BITS = 10;
    public static final int SEQUENCE_BITS = 12;
    public static final int MAX_NODE_ID = (1 << NODE_ID_BITS) - 1;
    static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private final Clock clock;
    private final long nodeId;

    private long lastTimestamp = -1;
    private long sequence = 0;

    public TraceIdGenerator(Clock clock, int nodeId) {
        if (clock == null) {
            throw new IllegalArgumentException("clock은 필수입니다.");
        }
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("nodeId는 0 이상 " + MAX_NODE_ID + " 이하여야 합니다. 입력값: " + nodeId);
        }
        this.clock = clock;
        this.nodeId = nodeId;
    }

    public synchronized String next() {
        long now = clock.millis() - EPOCH_MILLIS;
        if (now > lastTimestamp) {
            sequence = 0;
            lastTimestamp = now;
        } else {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                lastTimestamp = now == lastTimestamp ? waitUntilAfter(lastTimestamp) : lastTimestamp + 1;
            }
        }
        long id = (lastTimestamp << (NODE_ID_BITS + SEQUENCE_BITS)) | (nodeId << SEQUENCE_BITS) | sequence;
        return Long.toString(id);
    }

    private long waitUntilAfter(long timestamp) {
        long now = clock.millis() - EPOCH_MILLIS;
        while (now <= timestamp) {
            Thread.onSpinWait();
            now = clock.millis() - EPOCH_MILLIS;
        }
        return now;
    }
}
