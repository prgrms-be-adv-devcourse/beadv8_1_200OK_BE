package com.ok.refund.adapter.out.order;

import com.ok.common.exception.RestApiException;
import com.ok.common.trace.TraceHeaderInterceptor;
import com.ok.refund.exception.RefundErrorCode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

/**
 * 다른 모듈 내부 API를 부를 때 쓰는 RestClient를 만든다.
 * 주문 모듈의 같은 이름 클래스와 같은 값(연결 2초, 응답 3초)과 실패 처리를 쓴다. 그 클래스는 package-private이고
 * 다른 모듈 타입이라 가져다 쓸 수 없어 따로 둔다. 상품 클라이언트도 이 클래스로 만든다(지갑은 Kafka라 쓰지 않는다).
 */
public final class InternalRestClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2); // 연결까지 최대 2초
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);    // 응답까지 최대 3초

    private InternalRestClient() {
    }

    /**
     * @param baseUrl     호출할 모듈 주소
     * @param failureCode 호출이 실패했을 때 사용자에게 줄 에러 코드 (부르는 모듈마다 다름)
     */
    public static RestClient create(String baseUrl, RefundErrorCode failureCode) {
        // [1] 타임아웃
        // 설정이 없으면 상대가 응답하지 않을 때 환불 요청도 끝없이 기다린다
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)

                // [추적] 받는 쪽이 어느 요청·주문의 호출인지 알 수 있도록 MDC의 추적 키를 헤더로 보낸다
                .requestInterceptor(new TraceHeaderInterceptor())

                // [2] 응답 자체를 못 받은 경우 (서버 꺼짐, 타임아웃)
                // IOException을 그대로 두면 500이 되므로 요청을 보내는 순간을 감싸 우리 에러 코드로 바꾼다
                .requestInterceptor((request, body, execution) -> {
                    try {
                        return execution.execute(request, body);
                    } catch (IOException e) {
                        throw new RestApiException(failureCode);
                    }
                })

                // [3] 응답은 받았는데 오류 상태인 경우 (400, 500 등)
                // 메서드의 onStatus가 먼저 적용되므로 거기서 처리한 상태(조회의 404)는 여기 오지 않는다
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new RestApiException(failureCode);
                })
                .build();
    }
}
