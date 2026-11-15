package com.ok.common.jpa;

/**
 * 지금 처리 중인 HTTP 요청이 실행한 SQL 문 수를 스레드 단위로 센다.
 *
 * <p>Hibernate 통계는 애플리케이션 전체 합계라 엔드포인트별로 나눌 수 없어서, 요청 스레드에 따로 센다.
 * {@link #open()}으로 연 요청에서만 세고, 열리지 않은 스레드(스케줄러, 비동기 리스너 등)에서는 아무 일도 하지 않는다.
 * 요청 처리가 다른 스레드로 넘어가면 그 스레드의 SQL은 세지 않는다.
 */
public final class QueryCountContext {

    private static final ThreadLocal<Counter> CURRENT = new ThreadLocal<>();

    private QueryCountContext() {
    }

    /**
     * 현재 스레드에서 세기를 시작한다. 이미 열려 있으면 그 카운터를 이어서 쓰고, 닫아도 바깥 범위는 유지한다.
     */
    public static Scope open() {
        Counter previous = CURRENT.get();
        if (previous != null) {
            return new Scope(previous, previous);
        }
        Counter counter = new Counter();
        CURRENT.set(counter);
        return new Scope(counter, null);
    }

    static void increment() {
        Counter counter = CURRENT.get();
        if (counter != null) {
            counter.count++;
        }
    }

    private static final class Counter {
        private int count;
    }

    public static final class Scope implements AutoCloseable {

        private final Counter counter;
        private final Counter previous;

        private Scope(Counter counter, Counter previous) {
            this.counter = counter;
            this.previous = previous;
        }

        public int count() {
            return counter.count;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            }
        }
    }
}
