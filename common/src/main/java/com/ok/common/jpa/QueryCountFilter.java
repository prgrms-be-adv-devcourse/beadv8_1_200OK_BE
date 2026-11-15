package com.ok.common.jpa;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * 요청 하나가 실행한 SQL 문 수를 엔드포인트(메서드 + 템플릿 URI)별 분포 지표 {@value #METRIC_NAME}로 남긴다.
 *
 * <p>uri 태그는 {@code /products/{productId}} 같은 템플릿이라 값이 요청마다 늘지 않는다. 핸들러에 매핑되지 않은 요청
 * (인증 실패로 Security에서 끝난 401 등)은 템플릿이 없어 uri 태그가 사용자 입력이 되므로 기록하지 않는다.
 * 인증된 요청이 없는 경로를 부르면 정적 리소스 핸들러가 받아 uri가 {@code /**} 하나로 묶여 기록된다(실서버 통합 테스트로 확인).
 * 구간 경계({@link #SLO_BOUNDARIES})는 Prometheus가 요청당 쿼리 수의 분위수를 구할 수 있게 하는 히스토그램 버킷이다.
 */
public class QueryCountFilter extends OncePerRequestFilter {

    static final String METRIC_NAME = "jpa.queries.per.request";
    static final double[] SLO_BOUNDARIES = {1, 3, 5, 10, 20, 50, 100};

    private final MeterRegistry registry;

    public QueryCountFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (QueryCountContext.Scope scope = QueryCountContext.open()) {
            try {
                chain.doFilter(request, response);
            } finally {
                // 컨트롤러에서 예외가 나도 그 요청이 실행한 쿼리는 남긴다. 실패하는 요청에서 쿼리가 폭증하는 경우도 있다
                record(request, scope.count());
            }
        }
    }

    private void record(HttpServletRequest request, int count) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return;
        }
        DistributionSummary.builder(METRIC_NAME)
                .description("HTTP 요청 하나가 Hibernate로 실행한 SQL 문 수")
                .tag("method", request.getMethod())
                .tag("uri", pattern.toString())
                .serviceLevelObjectives(SLO_BOUNDARIES)
                .register(registry)
                .record(count);
    }
}
