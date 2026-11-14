package com.ok.carriersimulator.core;

import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * 보낼 알림의 대기 줄. 보낼 시각이 이른 것부터, 같으면 넣은 순서대로 꺼낸다.
 */
final class CallbackQueue {

    private final PriorityQueue<Pending> pending = new PriorityQueue<>(
            Comparator.comparing(Pending::dueAt).thenComparingLong(Pending::sequence));
    private long nextSequence;

    void add(CallbackAttempt attempt, Instant dueAt) {
        pending.add(new Pending(attempt, dueAt, nextSequence++));
    }

    /**
     * 보낼 시각이 된 알림을 하나 꺼낸다. 없으면 null이다.
     */
    CallbackAttempt pollDue(Instant now) {
        Pending head = pending.peek();
        if (head == null || head.dueAt.isAfter(now)) {
            return null;
        }
        return pending.poll().attempt;
    }

    int size() {
        return pending.size();
    }

    void clear() {
        pending.clear();
    }

    private record Pending(CallbackAttempt attempt, Instant dueAt, long sequence) {
    }
}
