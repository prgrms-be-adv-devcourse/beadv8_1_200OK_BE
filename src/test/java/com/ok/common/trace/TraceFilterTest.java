package com.ok.common.trace;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class TraceFilterTest {

    private final TraceFilter sut = new TraceFilter(new TraceIdGenerator(0));

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void 헤더가_없으면_새_traceId를_만들어_요청_처리_중_MDC에_넣고_응답에_담는다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(MDC.get(TraceContext.MDC_KEY));

        // when
        sut.doFilter(request, response, chain);

        // then
        assertThat(observed.get()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(observed.get()).matches("[0-9]+");
            softly.assertThat(response.getHeader(TraceContext.HEADER_NAME)).isEqualTo(observed.get());
            softly.assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
        });
    }

    @Test
    void 유효한_traceId_헤더가_있으면_그대로_쓴다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER_NAME, "client-trace_01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(MDC.get(TraceContext.MDC_KEY));

        // when
        sut.doFilter(request, response, chain);

        // then
        assertThat(observed.get()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(observed.get()).isEqualTo("client-trace_01");
            softly.assertThat(response.getHeader(TraceContext.HEADER_NAME)).isEqualTo("client-trace_01");
        });
    }

    @ParameterizedTest
    @MethodSource("유효하지_않은_traceId")
    void 유효하지_않은_traceId_헤더는_버리고_새로_만든다(String invalid) throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER_NAME, invalid);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        // when
        sut.doFilter(request, response, chain);

        // then
        assertThat(response.getHeader(TraceContext.HEADER_NAME)).matches("[0-9]+").isNotEqualTo(invalid);
    }

    static Stream<String> 유효하지_않은_traceId() {
        return Stream.of(
                "",
                " ",
                "has space",
                "trace\r\nSet-Cookie: x=1",
                "한글",
                "a".repeat(65),
                "id;drop"
        );
    }

    @Test
    void 체인에서_예외가_나도_MDC를_정리한다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("의도된 실패");
        };

        // when & then
        assertThatThrownBy(() -> sut.doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }
}
