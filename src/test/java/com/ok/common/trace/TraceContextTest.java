package com.ok.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        TraceContext.clearPending();
    }

    @Test
    void 범위를_열면_traceId가_설정되고_닫으면_사라진다() {
        // given
        assertThat(TraceContext.current()).isEmpty();

        // when
        try (TraceContext.Scope ignored = TraceContext.open("trace-1")) {
            // then
            assertThat(TraceContext.current()).contains("trace-1");
        }
        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    void 범위를_중첩하면_닫을_때_바깥_traceId로_돌아간다() {
        // given
        try (TraceContext.Scope outer = TraceContext.open("outer")) {
            // when
            try (TraceContext.Scope inner = TraceContext.open("inner")) {
                assertThat(TraceContext.current()).contains("inner");
            }

            // then
            assertThat(TraceContext.current()).contains("outer");
        }
    }

    @Test
    void 임시_보관한_값은_한_번만_꺼낼_수_있다() {
        // given
        TraceContext.setPending("pending-1");

        // when
        TraceContext.PendingTrace first = TraceContext.takePending();
        TraceContext.PendingTrace second = TraceContext.takePending();

        // then
        assertThat(first).isNotNull();
        assertThat(first.traceId()).isEqualTo("pending-1");
        assertThat(second).isNull();
    }

    @Test
    void null을_보관하면_traceId_없음_표시로_꺼내진다() {
        // given
        TraceContext.setPending("stale");

        // when
        TraceContext.setPending(null);
        TraceContext.PendingTrace taken = TraceContext.takePending();

        // then
        assertThat(taken).isNotNull();
        assertThat(taken.traceId()).isNull();
    }

    @Test
    void 보관한_값이_없으면_null이다() {
        // when
        TraceContext.PendingTrace taken = TraceContext.takePending();

        // then
        assertThat(taken).isNull();
    }
}
