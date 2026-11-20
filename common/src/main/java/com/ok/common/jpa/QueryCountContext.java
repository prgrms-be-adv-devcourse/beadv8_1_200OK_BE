package com.ok.common.jpa;

/**
 * 지금 처리 중인 HTTP 요청이 실행한 SQL 문 수를 스레드 단위로 센다.
 *
 * <p>Hibernate 통계는 애플리케이션 전체 합계라 엔드포인트별로 나눌 수 없어서, 요청 스레드에 따로 센다.
 * {@link #open()}으로 연 요청에서만 세고, 열리지 않은 스레드(스케줄러, 비동기 리스너 등)에서는 아무 일도 하지 않는다.
 * 요청 처리가 다른 스레드로 넘어가면 그 스레드의 SQL은 세지 않는다.
 */
public final class QueryCountContext {

    private static final ThreadLocal<QueryCounter> CURRENT = new ThreadLocal<>();

    private QueryCountContext() {
    }

    /**
     * 현재 스레드에서 세기를 시작한다. 이미 열려 있으면 그 카운터를 이어서 쓰고, 닫아도 바깥 범위는 유지한다.
     */
    public static Scope open() {
        QueryCounter existing = CURRENT.get();
        if (existing != null) {
            return new Scope(existing, false);
        }
        QueryCounter counter = new QueryCounter();
        CURRENT.set(counter);
        return new Scope(counter, true);
    }

    static void increment() {
        QueryCounter counter = CURRENT.get();
        if (counter != null) {
            counter.count++;
        }
    }

    private static final class QueryCounter {
        private int count;
    }

    public static final class Scope implements AutoCloseable {

        private final QueryCounter counter;
        // 이 범위가 카운터를 처음 연 쪽이면 true. 안쪽에서 다시 연 범위는 닫을 때 바깥의 카운터를 지우면 안 된다
        private final boolean owner;

        private Scope(QueryCounter counter, boolean owner) {
            this.counter = counter;
            this.owner = owner;
        }

        public int count() {
            return counter.count;
        }

        @Override
        public void close() {
            if (owner) {
                CURRENT.remove();
            }
        }
    }
}
