package com.ok.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
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
}
