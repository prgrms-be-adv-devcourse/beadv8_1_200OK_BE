package com.ok.common.trace;

import java.util.function.LongSupplier;

/**
 * Snowflake 방식으로 TraceID를 만든다. 41비트 시각(ms) + 10비트 노드 + 12비트 시퀀스.
 */
public class TraceIdGenerator {

    // 2026-01-01T00:00:00Z. 41비트 시각을 오래 쓰기 위해 이 시점부터의 경과 ms를 쓴다
    private static final long EPOCH_MILLIS = 1_767_225_600_000L;
    private static final int NODE_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_NODE_ID = (1L << NODE_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final int NODE_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_BITS;

    private final long nodeId;
    private final LongSupplier clock;
    private long lastTimestamp;
    private long sequence;

    public TraceIdGenerator(long nodeId) {
        this(nodeId, System::currentTimeMillis);
    }

    TraceIdGenerator(long nodeId, LongSupplier clock) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("trace.node-id는 0~" + MAX_NODE_ID + " 범위여야 합니다: " + nodeId);
        }
        this.nodeId = nodeId;
        this.clock = clock;
    }

    public synchronized String next() {
        long now = clock.getAsLong();
        if (now > lastTimestamp) {
            lastTimestamp = now;
            sequence = 0;
        } else {
            // 같은 ms이거나 시계가 뒤로 갔다. 마지막 시각을 유지한 채 시퀀스만 올려 중복과 역행을 피한다.
            // 시퀀스가 넘치면 다음 ms를 빌린다. 대기하거나 예외를 던지면 요청이 TraceID 때문에 실패하게 된다.
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                lastTimestamp++;
            }
        }
        long id = ((lastTimestamp - EPOCH_MILLIS) << TIMESTAMP_SHIFT) | (nodeId << NODE_SHIFT) | sequence;
        return Long.toString(id);
    }
}
