package com.ok.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TraceIdGeneratorTest {

    private static final Instant BASE = Instant.parse("2026-11-05T01:00:00Z");

    // 호출할 때마다 시각을 정해진 규칙으로 돌려주는 Clock. 시계 역행과 같은 ms 소진을 재현한다
    private static class ScriptedClock extends Clock {

        private final AtomicLong millis;
        private final java.util.function.LongUnaryOperator onRead;

        ScriptedClock(long startMillis, java.util.function.LongUnaryOperator onRead) {
            this.millis = new AtomicLong(startMillis);
            this.onRead = onRead;
        }

        @Override
        public long millis() {
            return millis.updateAndGet(onRead);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        void set(long value) {
            millis.set(value);
        }
    }

    private static Clock fixed() {
        return Clock.fixed(BASE, ZoneOffset.UTC);
    }

    private static long timestampOf(String id) {
        return Long.parseLong(id) >>> 22;
    }

    private static long nodeOf(String id) {
        return (Long.parseLong(id) >>> 12) & 0x3FF;
    }

    private static long sequenceOf(String id) {
        return Long.parseLong(id) & 0xFFF;
    }

    @Test
    void 시각_노드_시퀀스_비트로_값을_구성한다() {
        // given
        TraceIdGenerator sut = new TraceIdGenerator(fixed(), 37);

        // when
        String actual = sut.next();

        // then
        assertThat(actual).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(timestampOf(actual) + TraceIdGenerator.EPOCH_MILLIS).isEqualTo(BASE.toEpochMilli());
            softly.assertThat(nodeOf(actual)).isEqualTo(37);
            softly.assertThat(sequenceOf(actual)).isZero();
        });
    }

    @Test
    void 여러_스레드가_동시에_발급해도_값이_겹치지_않는다() throws Exception {
        // given
        TraceIdGenerator sut = new TraceIdGenerator(Clock.systemUTC(), 1);
        int threads = 16;
        int perThread = 20_000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // when
        try {
            List<Callable<List<String>>> tasks = IntStream.range(0, threads)
                    .<Callable<List<String>>>mapToObj(i -> () -> IntStream.range(0, perThread).mapToObj(j -> sut.next()).toList())
                    .toList();
            Set<String> all = new HashSet<>();
            for (Future<List<String>> future : executor.invokeAll(tasks)) {
                all.addAll(future.get(60, TimeUnit.SECONDS));
            }

            // then
            assertThat(all).hasSize(threads * perThread);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 한_스레드에서_발급한_값은_계속_커진다() {
        // given
        TraceIdGenerator sut = new TraceIdGenerator(Clock.systemUTC(), 0);

        // when
        List<Long> actual = IntStream.range(0, 50_000).mapToObj(i -> Long.parseLong(sut.next())).toList();

        // then
        assertThat(actual).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void 같은_ms에는_시퀀스가_1씩_늘어난다() {
        // given
        TraceIdGenerator sut = new TraceIdGenerator(fixed(), 0);

        // when
        String first = sut.next();
        String second = sut.next();

        // then
        assertThat(sequenceOf(second)).isEqualTo(sequenceOf(first) + 1);
    }

    @Test
    void 같은_ms의_시퀀스를_다_쓰면_다음_ms가_될_때까지_기다린다() {
        // given: 시퀀스를 다 쓴 뒤 기다리는 동안 시계를 세 번 읽는 사이에는 같은 ms이고, 그다음 읽을 때 1ms 지난다
        AtomicLong reads = new AtomicLong();
        ScriptedClock clock = new ScriptedClock(BASE.toEpochMilli(),
                current -> reads.incrementAndGet() > 4096 + 3 ? current + 1 : current);
        TraceIdGenerator sut = new TraceIdGenerator(clock, 0);
        List<String> ids = IntStream.range(0, 4096).mapToObj(i -> sut.next()).toList();

        // when
        String actual = sut.next();

        // then
        assertThat(actual).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sequenceOf(ids.get(4095))).isEqualTo(4095);
            softly.assertThat(timestampOf(actual)).isGreaterThan(timestampOf(ids.get(4095)));
            softly.assertThat(sequenceOf(actual)).isZero();
        });
    }

    @Test
    void 시계가_뒤로_가도_예외_없이_마지막_시각을_유지하며_값이_계속_커진다() {
        // given
        ScriptedClock clock = new ScriptedClock(BASE.toEpochMilli(), current -> current);
        TraceIdGenerator sut = new TraceIdGenerator(clock, 0);
        String before = sut.next();

        // when
        clock.set(BASE.toEpochMilli() - 5_000);
        String actual = sut.next();

        // then
        assertThat(actual).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(timestampOf(actual)).isEqualTo(timestampOf(before));
            softly.assertThat(Long.parseLong(actual)).isGreaterThan(Long.parseLong(before));
        });
    }

    @Test
    void 시계가_뒤로_간_상태에서_시퀀스를_다_써도_기다리지_않고_시각을_앞당긴다() {
        // given
        ScriptedClock clock = new ScriptedClock(BASE.toEpochMilli(), current -> current);
        TraceIdGenerator sut = new TraceIdGenerator(clock, 0);
        String before = sut.next();
        clock.set(BASE.toEpochMilli() - 5_000);
        IntStream.range(0, 4095).forEach(i -> sut.next());

        // when
        String actual = sut.next();

        // then
        assertThat(actual).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(timestampOf(actual)).isEqualTo(timestampOf(before) + 1);
            softly.assertThat(sequenceOf(actual)).isZero();
        });
    }

    @ParameterizedTest
    @MethodSource("허용되는_노드_번호")
    void 노드_번호의_경계값은_허용한다(int nodeId) {
        // when
        TraceIdGenerator sut = new TraceIdGenerator(fixed(), nodeId);

        // then
        assertThat(nodeOf(sut.next())).isEqualTo(nodeId);
    }

    static Stream<Integer> 허용되는_노드_번호() {
        return Stream.of(0, 1, 1023);
    }

    @ParameterizedTest
    @MethodSource("허용되지_않는_노드_번호")
    void 노드_번호가_범위를_벗어나면_거부한다(int nodeId) {
        // when & then
        assertThatThrownBy(() -> new TraceIdGenerator(fixed(), nodeId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nodeId는 0 이상 1023 이하여야 합니다. 입력값: " + nodeId);
    }

    static Stream<Integer> 허용되지_않는_노드_번호() {
        return Stream.of(-1, 1024, Integer.MAX_VALUE, Integer.MIN_VALUE);
    }

    @Test
    void Clock이_없으면_거부한다() {
        // when & then
        assertThatThrownBy(() -> new TraceIdGenerator(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clock은 필수입니다.");
    }
}
