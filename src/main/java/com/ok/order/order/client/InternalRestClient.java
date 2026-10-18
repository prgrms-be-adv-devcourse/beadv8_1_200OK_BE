package com.ok.order.order.client;

import com.ok.common.exception.RestApiException;
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
final class InternalRestClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2); // 연결까지 최대 2초
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);    // 응답까지 최대 3초

    private InternalRestClient() {
    }

    /**
     * @param baseUrl     호출할 모듈 주소
     * @param failureCode 호출이 실패했을 때 사용자에게 줄 에러 코드 (모듈마다 다름)
     */
    static RestClient create(String baseUrl, OrderErrorCode failureCode) {
        // [1] 타임아웃
        // 설정이 없으면 상대가 응답하지 않을 때 주문 요청도 끝없이 기다린다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)

                // [2] 응답 자체를 못 받은 경우 (서버 꺼짐, 타임아웃)
                // 이때는 IOException이 나고, 그대로 두면 500이 된다.
                // 요청을 보내는 순간을 감싸서, IOException이면 우리 에러 코드로 바꾼다.
                .requestInterceptor((request, body, execution) -> {
                    try {
                        return execution.execute(request, body);
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