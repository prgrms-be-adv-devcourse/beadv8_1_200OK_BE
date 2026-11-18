package com.ok.order.order.adapter.out.http;

import com.ok.common.exception.RestApiException;
import com.ok.common.trace.TraceHeaderInterceptor;
import com.ok.order.order.exception.OrderErrorCode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

/**
 * 다른 모듈 내부 API를 부를 때 쓰는 RestClient를 만든다.
 * 상품·지갑·배송 client가 같은 실패 처리를 쓰도록 한 곳에 모았다.
 */
public final class InternalRestClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2); // 연결까지 최대 2초
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);    // 응답까지 최대 3초

    private InternalRestClient() {
    }

    /**
     * @param baseUrl     호출할 모듈 주소
     * @param failureCode 호출이 실패했을 때 사용자에게 줄 에러 코드 (모듈마다 다름)
     */
    public static RestClient create(String baseUrl, OrderErrorCode failureCode) {
        return create(baseUrl, failureCode, READ_TIMEOUT);
    }

    // 응답 시간 초과를 짧게 두고 확인하려는 테스트용
    static RestClient create(String baseUrl, OrderErrorCode failureCode, Duration readTimeout) {
        // [1] 타임아웃
        // 설정이 없으면 상대가 응답하지 않을 때 주문 요청도 끝없이 기다린다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)

                // [추적] 받는 쪽이 어느 요청·주문의 호출인지 알 수 있도록 MDC의 추적 키를 헤더로 보낸다
                .requestInterceptor(new TraceHeaderInterceptor())

                // [2] 응답을 끝까지 받지 못한 경우 (서버 꺼짐, 타임아웃)
                // 이때는 IOException이 나고, 그대로 두면 500이 된다.
                // 요청을 보내고 응답(상태 코드·본문)을 끝까지 읽는 구간을 감싸서, IOException이면 우리 에러 코드(503)로 바꾼다.
                // 보내기만 감싸면 상태 코드나 본문을 읽다가 난 시간 초과는 이 구간 밖(클라이언트의 body 변환)에서 나 500이 된다(#739, 3차 부하테스트 1,033건).
                // 본문은 다 읽은 뒤 변환하므로, 형식이 깨진 응답처럼 다시 해도 낫지 않는 실패는 지금처럼 500으로 남는다
                .requestInterceptor((request, body, execution) -> {
                    try {
                        return BufferedClientHttpResponse.readFully(execution.execute(request, body));
                    } catch (IOException e) {
                        throw new RestApiException(failureCode);
                    }
                })

                // [3] 응답은 받았는데 에러 상태인 경우
                // 메서드에서 onStatus로 처리하지 않은 (400, 422, 500 등)가 여기로 온다.
                // 메서드의 onStatus가 먼저 적용되므로 allocateStock의 404·409는 여기 오지 않는다.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new RestApiException(failureCode);
                })
                .build();
    }
}