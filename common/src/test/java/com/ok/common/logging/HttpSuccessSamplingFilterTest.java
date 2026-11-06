package com.ok.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;

class HttpSuccessSamplingFilterTest {

    private final LoggerContext context = new LoggerContext();

    @BeforeEach
    void resetCounter() {
        LogDropCounter.reset();
    }

    static Stream<Object[]> 비율과_통과_범위() {
        return Stream.of(
                new Object[] {0.0, 0, 0},
                new Object[] {1.0, 1000, 1000},
                new Object[] {0.1, 50, 160});
    }

    @ParameterizedTest
    @MethodSource("비율과_통과_범위")
    void HTTP_IN_성공_로그는_설정한_비율만_통과한다(double rate, int minPassed, int maxPassed) {
        // given
        HttpSuccessSamplingFilter sut = new HttpSuccessSamplingFilter();
        sut.setRate(rate);

        // when
        long passed = IntStream.range(0, 1000)
                .filter(i -> sut.decide(event(Level.INFO, "HTTP_IN")) == FilterReply.NEUTRAL)
                .count();

        // then
        assertThat(passed).isBetween((long) minPassed, (long) maxPassed);
        assertThat(LogDropCounter.count(LogDropCounter.Reason.SAMPLED)).isEqualTo(1000 - passed);
    }

    @Test
    void HTTP_IN이_아닌_로그는_비율이_0이어도_건드리지_않는다() {
        // given
        HttpSuccessSamplingFilter sut = new HttpSuccessSamplingFilter();
        sut.setRate(0.0);

        // when & then
        assertThat(sut.decide(event(Level.INFO, "STATE_CHANGED"))).isEqualTo(FilterReply.NEUTRAL);
        assertThat(sut.decide(event(Level.INFO, null))).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void 비율이_0과_1_사이가_아니면_설정에_실패한다() {
        // given
        HttpSuccessSamplingFilter sut = new HttpSuccessSamplingFilter();

        // when & then
        assertThatThrownBy(() -> sut.setRate(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sut.setRate(-0.1)).isInstanceOf(IllegalArgumentException.class);
    }

    private ILoggingEvent event(Level level, String eventType) {
        LoggingEvent event = new LoggingEvent("fqcn", context.getLogger("com.ok.common.Http"), level, "요청", null, null);
        event.setMDCPropertyMap(eventType == null ? Map.of() : Map.of("eventType", eventType));
        return event;
    }
}
