package com.ok.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

class TraceHeaderInterceptorTest {

    private final TraceHeaderInterceptor sut = new TraceHeaderInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void MDC에_있는_추적_키를_헤더로_보낸다() throws Exception {
        // given
        try (TraceContext.Scope ignored = TraceContext.open("111")) {
            TraceContext.bindOrderId(222L);
            TraceContext.bindShipmentId(333L);
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, java.net.URI.create("http://localhost/x"));

            // when
            sut.intercept(request, new byte[0], noopExecution());

            // then
            HttpHeaders headers = request.getHeaders();
            assertSoftly(softly -> {
                softly.assertThat(headers.getFirst("X-Trace-Id")).isEqualTo("111");
                softly.assertThat(headers.getFirst("X-Order-Id")).isEqualTo("222");
                softly.assertThat(headers.getFirst("X-Shipment-Id")).isEqualTo("333");
            });
        }
    }

    @Test
    void MDC에_없는_키는_헤더로_보내지_않는다() throws Exception {
        // given
        try (TraceContext.Scope ignored = TraceContext.open("111")) {
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, java.net.URI.create("http://localhost/x"));

            // when
            sut.intercept(request, new byte[0], noopExecution());

            // then
            assertThat(request.getHeaders().headerNames()).contains("X-Trace-Id").doesNotContain("X-Order-Id", "X-Shipment-Id");
        }
    }

    @Test
    void 추적_범위_밖이면_헤더를_보내지_않는다() throws Exception {
        // given
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, java.net.URI.create("http://localhost/x"));

        // when
        sut.intercept(request, new byte[0], noopExecution());

        // then
        assertThat(request.getHeaders().headerNames()).isEmpty();
    }

    private static ClientHttpRequestExecution noopExecution() {
        return (request, body) -> (ClientHttpResponse) null;
    }
}
