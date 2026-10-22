package com.ok.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 요청마다 TraceID를 정해 MDC에 넣고 응답 헤더로 돌려준다.
 * 빈으로 스캔되지 않도록 {@code @Component}를 붙이지 않는다. {@code @WebMvcTest}가 Filter 빈을 스캔하는데
 * TraceIdGenerator 빈이 없어 컨텍스트가 깨지기 때문이다. 등록은 {@code com.ok.config.TraceConfig}가 한다.
 */
@RequiredArgsConstructor
public class TraceFilter extends OncePerRequestFilter {

    // 외부 입력이라 로그·응답 헤더에 그대로 쓰기 전에 형식을 제한한다 (줄바꿈 주입, 과도한 길이 방지)
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final TraceIdGenerator traceIdGenerator;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TraceContext.HEADER_NAME));
        response.setHeader(TraceContext.HEADER_NAME, traceId);

        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {
            chain.doFilter(request, response);
        }
    }

    private String resolveTraceId(String requested) {
        if (requested != null && VALID_TRACE_ID.matcher(requested).matches()) {
            return requested;
        }
        return traceIdGenerator.next();
    }
}
