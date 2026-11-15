package com.ok.refund.adapter.out.product;

import com.ok.refund.adapter.out.order.InternalRestClient;
import com.ok.refund.adapter.out.product.dto.RefundStockPartialReleaseRequest;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.common.exception.RestApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 상품 모듈 내부 API를 HTTP로 불러 환불한 개수만큼 재고를 되돌린다(#363 재고 일부 해제).
 * 이름에 Refund를 붙인 이유: 다른 모듈에도 상품 호출 클라이언트가 있어, monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 */
@Component
public class RefundProductHttpAdapter implements ProductStockReleasePort {

    private final RestClient restClient;

    // 주소 설정은 주문 어댑터와 같은 키·기본값을 쓴다. 한 앱(monolith)에서는 자기 자신을 부르게 된다
    public RefundProductHttpAdapter(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/products", RefundErrorCode.PRODUCT_API_FAILED);
    }

    // 상품 API: POST /internal/v1/products/stock-allocations/{orderId}/partial-release, 성공은 204(같은 refundId로 같은 내용이면 다시 204).
    // 실패를 다시 시도할지로 나눈다(#576). 상품 API 문서가 4xx(404 배정 기록 없음, 409 해제 가능 수량 초과·같은 환불 다른 내용,
    // 422 요청 값 오류)를 "다시 해도 결과가 같음"으로 정하므로 PRODUCT_RELEASE_REFUSED로 바꾸고, 지휘자는 다시 시도하지 않고 단계를 멈춘다.
    // 연결 실패·타임아웃·5xx는 잠깐의 실패일 수 있어 InternalRestClient가 바꾸는 PRODUCT_API_FAILED 그대로 두고 알람이 다시 시도한다.
    // 4xx 안의 이유(type)는 가르지 않는다. type은 PROBLEM_BASE_URI 설정이 기본값이면 about:blank라 믿을 수 없고, 어느 이유든 처리가 같다
    @Override
    public void releaseStock(StockRelease release) {
        restClient.post()
                .uri("/stock-allocations/{orderId}/partial-release", release.orderId())
                .body(RefundStockPartialReleaseRequest.from(release))
                .retrieve()
                // 메서드의 onStatus가 InternalRestClient의 기본 상태 처리보다 먼저 적용된다
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new RestApiException(RefundErrorCode.PRODUCT_RELEASE_REFUSED);
                })
                .toBodilessEntity();
    }
}
