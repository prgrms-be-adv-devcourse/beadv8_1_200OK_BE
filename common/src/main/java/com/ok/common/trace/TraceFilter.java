package com.ok.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.regex.Pattern;

/**
 * 요청마다 traceId를 정해 MDC에 넣고 응답 헤더로 돌려준다.
 *
 * <p>요청 헤더 {@code X-Trace-Id}는 서버끼리 부르는 {@code /internal/} 요청에서만, 그중에서도 Spring Security의 {@code /internal/**}
 * 접근 조건과 같게 loopback 주소에서 온 요청에서만 이어받는다. 공개 API는 헤더를 보내도 항상 새로 발급한다.
 * traceId는 flowwatch 이력의 {@code eventKey} 구분자로 쓰이므로, 외부 호출자가 이전 요청의 traceId를 보내 새 사건이 중복으로 버려지게 하는 것을 막기 위해서다.
 *
 * <p>이어받는 값은 0으로 시작하지 않는 숫자 15~18자리여야 한다. 발급기가 만드는 값은 기준 시각 6시간 뒤부터 항상 15자리 이상이라,
 * 그보다 짧은 값은 발급기가 만든 값이 아니고 발급 시각 역산도 틀리게 만든다. 18자리 수는 모두 long 안에 들어가므로 long 범위 검사를 따로 하지 않아도 된다.
 * 그 밖의 값은 무시하고 새로 발급한다. 발급기가 만드는 값은 2033년 중반부터 19자리가 되어, 그 이후 값은 이어받지 못하고 새로 발급된다.
 *
 * <p>같은 조건({@code /internal/}, loopback)에서 {@code X-Order-Id}, {@code X-Shipment-Id}도 0으로 시작하지 않는 숫자 1~18자리면 이어받는다.
 * 이 헤더는 서버끼리 부르는 호출에만 믿을 수 있다. 공개 API에서 받으면 호출자가 임의의 주문 번호로 로그를 오염시킬 수 있다.
 */
public class TraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    public static final String HEADER = "X-Trace-Id";
    public static final String ORDER_HEADER = "X-Order-Id";
    public static final String SHIPMENT_HEADER = "X-Shipment-Id";
    private static final String INTERNAL_PATH_PREFIX = "/internal/";
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[1-9][0-9]{14,17}$");
    private static final Pattern VALID_ID = Pattern.compile("^[1-9][0-9]{0,17}$");

    private final TraceIdGenerator generator;

    public TraceFilter(TraceIdGenerator generator) {
        this.generator = generator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean internal = request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX) && isLoopback(request);
        String traceId = internal ? inheritTraceId(request) : null;
        if (traceId == null) {
            traceId = generator.next();
        }

        // 본문을 쓰기 시작하면 응답이 커밋되어 헤더를 못 넣으므로 체인을 타기 전에 넣는다
        response.setHeader(HEADER, traceId);
        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {
            if (internal) {
                restoreKey(request, ORDER_HEADER, TraceContext.ORDER_ID);
                restoreKey(request, SHIPMENT_HEADER, TraceContext.SHIPMENT_ID);
            }
            chain.doFilter(request, response);
        }
    }

    // 서버끼리 부르는 호출이 보낸 값의 형식이 틀리면 추적이 조용히 끊기므로 알 수 있게 남긴다. 로그 주입을 막으려고 값은 찍지 않고 길이만 남긴다
    private static String inheritTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        if (incoming == null) {
            return null;
        }
        if (VALID_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        log.warn("내부 요청의 {} 형식이 올바르지 않아 새로 발급한다. 값의 길이={}", HEADER, incoming.length());
        return null;
    }

    // 이 필터는 Security보다 앞이라 loopback 검사에 막힐 호출자의 헤더도 먼저 읽게 된다. 막힐 호출이 임의의 주문 번호를 로그에 남기지 못하게 같은 조건을 건다
    private static boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.ofLiteral(request.getRemoteAddr()).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void restoreKey(HttpServletRequest request, String header, String mdcKey) {
        String value = validId(request.getHeader(header));
        if (value != null) {
            MDC.put(mdcKey, value);
        }
    }

    private static String validId(String value) {
        return value != null && VALID_ID.matcher(value).matches() ? value : null;
    }
}
