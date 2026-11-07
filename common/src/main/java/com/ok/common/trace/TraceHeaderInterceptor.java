package com.ok.common.trace;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 내부 API 호출에 현재 MDC의 추적 키를 헤더로 실어 보낸다. MDC에 값이 없는 키는 보내지 않는다.
 *
 * <p>받는 쪽의 {@link TraceFilter}가 헤더로 컨텍스트를 복원한다. 헤더가 없으면 받는 쪽은 어느 주문의 호출인지 알 수 없다.
 */
public class TraceHeaderInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        addIfPresent(request, TraceFilter.HEADER, TraceContext.TRACE_ID);
        addIfPresent(request, TraceFilter.ORDER_HEADER, TraceContext.ORDER_ID);
        addIfPresent(request, TraceFilter.SHIPMENT_HEADER, TraceContext.SHIPMENT_ID);
        return execution.execute(request, body);
    }

    private static void addIfPresent(HttpRequest request, String header, String mdcKey) {
        String value = MDC.get(mdcKey);
        if (value != null) {
            // 호출부가 같은 이름의 헤더를 넣었어도 MDC 값이 기준이다. 한 요청 안에서 추적 키가 갈라지지 않게 덮어쓴다
            request.getHeaders().set(header, value);
        }
    }
}
