package com.ok.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 요청마다 traceId를 정해 MDC에 넣고 응답 헤더로 돌려준다.
 *
 * <p>요청 헤더 {@code X-Trace-Id}가 0으로 시작하지 않는 숫자 15~18자리면 이어받는다. 발급기가 만드는 값은 기준 시각 6시간 뒤부터 항상 15자리 이상이라, 그보다 짧은 값은 발급기가 만든 값이 아니고 발급 시각 역산도 틀리게 만든다. 18자리 수는 모두 long 안에 들어가므로, 헤더로 임의의 문자나 긴 값을
 * 넣어 로그를 오염시키는 것을 막으면서 long 범위 검사를 따로 하지 않아도 된다. 그 밖의 값(문자, 너무 긴 값)은 무시하고 새로 발급한다.
 * 발급기가 만드는 값은 2033년 중반부터 19자리가 되어, 그 이후 값은 이어받지 못하고 새로 발급된다.
 */
public class TraceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[1-9][0-9]{14,17}$");

    private final TraceIdGenerator generator;

    public TraceFilter(TraceIdGenerator generator) {
        this.generator = generator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && VALID_TRACE_ID.matcher(incoming).matches() ? incoming : generator.next();

        // 본문을 쓰기 시작하면 응답이 커밋되어 헤더를 못 넣으므로 체인을 타기 전에 넣는다
        response.setHeader(HEADER, traceId);
        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {
            chain.doFilter(request, response);
        }
    }
}
