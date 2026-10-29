package com.ok.refund.client;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

/**
 * 다른 모듈 내부 API를 부를 때 쓰는 RestClient를 만든다.
 * 주문 모듈의 같은 이름 클래스와 같은 값(연결 2초, 응답 3초)과 실패 처리를 쓴다. 그 클래스는 package-private이라 가져다 쓸 수 없다.
 */
@Slf4j
final class InternalRestClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2); // 연결까지 최대 2초
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);    // 응답까지 최대 3초

    private InternalRestClient() {
    }

    /**
     * @param baseUrl     호출할 모듈 주소
     * @param failureCode 호출이 실패했을 때 사용자에게 줄 에러 코드 (모듈마다 다름)
     */
    static RestClient create(String baseUrl, RefundErrorCode failureCode) {
        // 설정이 없으면 상대가 응답하지 않을 때 환불 승인도 끝없이 기다린다. 즉시 재시도는 하지 않는다(설계 6장)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                // 응답 자체를 못 받은 경우(서버 꺼짐, 타임아웃). 그대로 두면 500이 되므로 모듈 에러 코드로 바꾼다
                .requestInterceptor((request, body, execution) -> {
                    try {
                        return execution.execute(request, body);
                    } catch (IOException e) {
                        log.warn("다른 모듈 호출 실패(응답 없음). uri={}, reason={}", request.getURI(), e.getClass().getSimpleName());
                        throw new RestApiException(failureCode);
                    }
                })
                // 메서드에서 onStatus로 처리하지 않은 오류 상태. 실패 기록에는 에러 코드만 남으므로 실제 상태는 로그로 남긴다
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    log.warn("다른 모듈 호출 실패. uri={}, status={}", request.getURI(), response.getStatusCode().value());
                    throw new RestApiException(failureCode);
                })
                .build();
    }
}
