package com.ok.common.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIdGeneratorTest {

    private static final long BASE_MILLIS = 1_800_000_000_000L;

    static Stream<Long> 범위_밖_노드_ID() {
        return Stream.of(-1L, 1024L, Long.MAX_VALUE);
    }

    @Test
    void 설정한_노드_ID가_ID에_담긴다() {
        // given
        TraceIdGenerator sut = new TraceIdGenerator(37, () -> BASE_MILLIS);

        // when
        long id = Long.parseLong(sut.next());

        // then
        assertThat((id >> 12) & 0x3FF).isEqualTo(37);
    }

    @Test
    void ID의_상위_비트에서_생성_시각을_복원할_수_있다() {
        // given
        TraceIdGenerator sut = new TraceIdGenerator(0, () -> BASE_MILLIS);

        // when
        long id = Long.parseLong(sut.next());

        // then
        // 2026-01-01T00:00:00Z부터의 경과 ms가 12(시퀀스) + 10(노드) 비트 위에 들어 있다. 장애 때 ID만으로 시점을 좁히는 근거
        assertThat((id >> 22) + 1_767_225_600_000L).isEqualTo(BASE_MILLIS);
    }

    @ParameterizedTest
    @MethodSource("범위_밖_노드_ID")
    void 노드_ID가_범위를_벗어나면_생성할_수_없다(long nodeId) {
        // when & then
        assertThatThrownBy(() -> new TraceIdGenerator(nodeId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_ms에_4096건을_넘게_만들어도_중복되지_않고_증가한다() {
        // given
        TraceIdGenerator sut = new TraceIdGenerator(0, () -> BASE_MILLIS);

        // when
        long[] ids = LongStream.range(0, 10_000).map(i -> Long.parseLong(sut.next())).toArray();

        // then
        Set<Long> unique = new HashSet<>();
        for (int i = 0; i < ids.length; i++) {
            unique.add(ids[i]);
            if (i > 0) {
                assertThat(ids[i]).isGreaterThan(ids[i - 1]);
            }
        }
        assertThat(unique).hasSize(ids.length);
    }

    @Test
    void 시계가_뒤로_가도_중복되지_않고_증가한다() {
        // given
        long[] times = {BASE_MILLIS, BASE_MILLIS - 50, BASE_MILLIS - 50, BASE_MILLIS + 1};
        int[] index = {0};
        TraceIdGenerator sut = new TraceIdGenerator(0, () -> times[index[0]++]);

        // when
        long first = Long.parseLong(sut.next());
        long second = Long.parseLong(sut.next());
        long third = Long.parseLong(sut.next());
        long fourth = Long.parseLong(sut.next());

        // then
        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
        assertThat(fourth).isGreaterThan(third);
    }

    @Test
    void 반환값은_10진수_문자열이다() {
        // given
        TraceIdGenerator sut = new TraceIdGenerator(0);

        // when
        String id = sut.next();

        // then
        assertThat(id).matches("[0-9]+");
    }
}
