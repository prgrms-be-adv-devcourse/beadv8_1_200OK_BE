package com.ok.common.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class QueryCountFilterTest {

    private static final String METRIC = "jpa.queries.per.request";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueryCountFilter sut = new QueryCountFilter(registry);
    private final QueryCountStatementInspector inspector = new QueryCountStatementInspector();

    @Test
    void 요청이_실행한_SQL_수를_메서드와_템플릿_URI별로_기록한다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products/42");
        FilterChain chain = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/products/{productId}");
            inspector.inspect("select p");
            inspector.inspect("select o");
            inspector.inspect("select o");
        };

        // when
        sut.doFilter(request, new MockHttpServletResponse(), chain);

        // then
        DistributionSummary summary = registry.find(METRIC)
                .tag("method", "GET").tag("uri", "/api/v1/products/{productId}").summary();
        assertThat(summary).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(summary.count()).isEqualTo(1);
            softly.assertThat(summary.totalAmount()).isEqualTo(3.0);
        });
    }

    @Test
    void 같은_엔드포인트의_여러_요청은_하나의_분포로_쌓인다() throws Exception {
        // given
        FilterChain few = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/orders");
            inspector.inspect("select 1");
        };
        FilterChain many = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/orders");
            for (int i = 0; i < 31; i++) {
                inspector.inspect("select n+1");
            }
        };

        // when
        sut.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), new MockHttpServletResponse(), few);
        sut.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), new MockHttpServletResponse(), many);

        // then
        DistributionSummary summary = registry.find(METRIC).tag("uri", "/api/v1/orders").summary();
        assertThat(summary).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(summary.count()).isEqualTo(2);
            softly.assertThat(summary.max()).isEqualTo(31.0);
        });
    }

    @Test
    void SQL을_실행하지_않은_요청도_0으로_기록한다() throws Exception {
        // given
        FilterChain chain = (req, res) ->
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/ping");

        // when
        sut.doFilter(new MockHttpServletRequest("GET", "/api/v1/ping"), new MockHttpServletResponse(), chain);

        // then
        DistributionSummary summary = registry.find(METRIC).tag("uri", "/api/v1/ping").summary();
        assertThat(summary).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(summary.count()).isEqualTo(1);
            softly.assertThat(summary.totalAmount()).isZero();
        });
    }

    @Test
    void 컨트롤러에_매핑되지_않은_요청은_기록하지_않는다() throws Exception {
        // given
        FilterChain chain = (req, res) -> inspector.inspect("select 1");

        // when
        sut.doFilter(new MockHttpServletRequest("GET", "/no/such/path/123"), new MockHttpServletResponse(), chain);

        // then
        assertThat(registry.find(METRIC).summaries()).isEmpty();
    }

    @Test
    void 처리_중_예외가_나도_그_요청의_SQL_수를_기록하고_예외를_그대로_던진다() {
        // given
        FilterChain chain = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/orders");
            inspector.inspect("select 1");
            inspector.inspect("select 2");
            throw new IllegalStateException("실패");
        };

        // when & then
        assertThatThrownBy(() -> sut.doFilter(new MockHttpServletRequest("POST", "/api/v1/orders"),
                new MockHttpServletResponse(), chain))
                .isInstanceOf(IllegalStateException.class);
        DistributionSummary summary = registry.find(METRIC).tag("method", "POST").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.totalAmount()).isEqualTo(2.0);
    }

    @Test
    void 요청이_끝나면_카운터가_정리되어_다음_요청에_섞이지_않는다() throws Exception {
        // given
        FilterChain first = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/a");
            inspector.inspect("select 1");
        };
        FilterChain second = (req, res) ->
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/b");

        // when
        sut.doFilter(new MockHttpServletRequest("GET", "/a"), new MockHttpServletResponse(), first);
        sut.doFilter(new MockHttpServletRequest("GET", "/b"), new MockHttpServletResponse(), second);

        // then
        DistributionSummary summary = registry.find(METRIC).tag("uri", "/b").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.totalAmount()).isZero();
    }

    @Test
    void Prometheus에는_구간_버킷과_합계가_메트릭_이름과_태그로_노출된다() throws Exception {
        // given
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        QueryCountFilter prometheusFilter = new QueryCountFilter(prometheus);
        FilterChain chain = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/orders");
            inspector.inspect("select 1");
        };

        // when
        prometheusFilter.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), new MockHttpServletResponse(), chain);

        // then
        String sut = prometheus.scrape();
        assertSoftly(softly -> {
            softly.assertThat(sut).contains("jpa_queries_per_request_bucket{method=\"GET\",uri=\"/api/v1/orders\",le=\"5.0\"} 1");
            softly.assertThat(sut).contains("jpa_queries_per_request_count{method=\"GET\",uri=\"/api/v1/orders\"} 1");
            softly.assertThat(sut).contains("jpa_queries_per_request_sum{method=\"GET\",uri=\"/api/v1/orders\"} 1.0");
        });
    }
}
