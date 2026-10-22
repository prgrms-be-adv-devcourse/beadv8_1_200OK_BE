package com.ok.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class TraceScheduledTaskDecoratorTest {

    private final TraceScheduledTaskDecorator sut = new TraceScheduledTaskDecorator(new TraceIdGenerator(0));

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void 작업이_실행되는_동안_traceId가_있고_끝나면_사라진다() {
        // given
        List<String> observed = new ArrayList<>();
        Runnable decorated = sut.decorate(() -> observed.add(MDC.get(TraceContext.MDC_KEY)));

        // when
        decorated.run();

        // then
        assertThat(observed).hasSize(1);
        assertSoftly(softly -> {
            softly.assertThat(observed.get(0)).matches("[0-9]+");
            softly.assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
        });
    }

    @Test
    void 같은_작업을_반복_실행하면_실행마다_다른_traceId를_가진다() {
        // given
        List<String> observed = new ArrayList<>();
        Runnable decorated = sut.decorate(() -> observed.add(MDC.get(TraceContext.MDC_KEY)));

        // when
        decorated.run();
        decorated.run();
        decorated.run();

        // then
        assertThat(Set.copyOf(observed)).hasSize(3);
    }

    @Test
    void 이미_traceId가_있는_스레드에서_실행해도_새로_만들고_끝나면_원래_값으로_돌아간다() {
        // given: 크론이 이전 실행 도중에 다음 실행을 예약하는 상황을 흉내 낸다
        MDC.put(TraceContext.MDC_KEY, "previous-run");
        List<String> observed = new ArrayList<>();
        Runnable decorated = sut.decorate(() -> observed.add(MDC.get(TraceContext.MDC_KEY)));

        // when
        decorated.run();

        // then
        assertThat(observed).hasSize(1);
        assertSoftly(softly -> {
            softly.assertThat(observed.get(0)).isNotEqualTo("previous-run");
            softly.assertThat(MDC.get(TraceContext.MDC_KEY)).isEqualTo("previous-run");
        });
    }

    @Test
    void 작업이_예외로_끝나도_traceId를_정리한다() {
        // given
        Runnable decorated = sut.decorate(() -> {
            throw new IllegalStateException("의도된 실패");
        });

        // when & then
        assertThatThrownBy(decorated::run).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }
}
