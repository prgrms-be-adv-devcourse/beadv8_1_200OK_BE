package com.ok.carriersimulator.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PlaybackClockTest {

    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");

    @Test
    void 하루를_10초로_재생하면_실제_10초에_가상_하루가_지난다() {
        // given
        PlaybackClock sut = new PlaybackClock(START, 10);

        // when
        Instant virtual = sut.virtualNow(START.plusSeconds(10));

        // then
        assertThat(virtual).isEqualTo(START.plus(Duration.ofDays(1)));
    }

    @Test
    void 속도를_바꿔도_이미_지난_가상_시각의_실제_시각은_그대로다() {
        // given: 가상 1시간 = 실제 1초
        PlaybackClock sut = new PlaybackClock(START, 24);
        Instant fourHours = START.plus(Duration.ofHours(4));
        Instant before = sut.realTimeOf(fourHours, START.plusSeconds(10));

        // when: 10초에 속도를 두 배로 올린다
        sut.changeSpeed(12, START.plusSeconds(10));

        // then
        assertSoftly(softly -> {
            softly.assertThat(before).isEqualTo(START.plusSeconds(4));
            softly.assertThat(sut.realTimeOf(fourHours, START.plusSeconds(20))).isEqualTo(START.plusSeconds(4));
            // 바꾼 뒤로는 실제 1초에 가상 2시간이 흐른다
            softly.assertThat(sut.virtualNow(START.plusSeconds(11))).isEqualTo(START.plus(Duration.ofHours(12)));
            softly.assertThat(sut.realTimeOf(START.plus(Duration.ofHours(12)), START.plusSeconds(20)))
                    .isEqualTo(START.plusSeconds(11));
        });
    }

    @Test
    void 일시정지_중에는_가상_시각이_멈춘다() {
        // given
        PlaybackClock sut = new PlaybackClock(START, 24);

        // when: 5초에 멈추고 15초에 다시 재생한다
        sut.pause(START.plusSeconds(5));
        Instant whilePaused = sut.virtualNow(START.plusSeconds(12));
        sut.resume(START.plusSeconds(15));

        // then
        assertSoftly(softly -> {
            softly.assertThat(whilePaused).isEqualTo(START.plus(Duration.ofHours(5)));
            softly.assertThat(sut.virtualNow(START.plusSeconds(16))).isEqualTo(START.plus(Duration.ofHours(6)));
            softly.assertThat(sut.realTimeOf(START.plus(Duration.ofHours(5)), START.plusSeconds(16)))
                    .isEqualTo(START.plusSeconds(5));
            softly.assertThat(sut.paused()).isFalse();
        });
    }

    @Test
    void 건너뛴_구간의_가상_시각은_건너뛴_순간에_대응한다() {
        // given
        PlaybackClock sut = new PlaybackClock(START, 24);

        // when: 3초(가상 3시간)에 하루를 건너뛴다
        sut.skip(Duration.ofDays(1), START.plusSeconds(3));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.virtualNow(START.plusSeconds(3))).isEqualTo(START.plus(Duration.ofHours(27)));
            softly.assertThat(sut.realTimeOf(START.plus(Duration.ofHours(10)), START.plusSeconds(3)))
                    .isEqualTo(START.plusSeconds(3));
            softly.assertThat(sut.realTimeOf(START.plus(Duration.ofHours(2)), START.plusSeconds(3)))
                    .isEqualTo(START.plusSeconds(2));
            softly.assertThat(sut.realTimeOf(START.plus(Duration.ofHours(28)), START.plusSeconds(4)))
                    .isEqualTo(START.plusSeconds(4));
        });
    }

    @Test
    void 도달하지_않은_가상_시각을_물으면_예외다() {
        // given
        PlaybackClock sut = new PlaybackClock(START, 24);

        // when & then
        assertThatThrownBy(() -> sut.realTimeOf(START.plus(Duration.ofHours(2)), START.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("아직 도달하지 않은 가상 시각입니다");
    }

    @ParameterizedTest
    @MethodSource("잘못된_속도")
    void 재생_속도가_0_이하면_예외다(double secondsPerDay) {
        // when & then
        assertThatThrownBy(() -> new PlaybackClock(START, secondsPerDay))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재생 속도는 0보다 커야 합니다.");
    }

    static Stream<Double> 잘못된_속도() {
        return Stream.of(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY);
    }
}
