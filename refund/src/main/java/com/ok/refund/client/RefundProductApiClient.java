package com.ok.refund.client;

import com.ok.refund.client.dto.StockPartialReleaseRequest;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.RefundProductClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 상품 모듈 내부 API를 HTTP로 부른다. 경로와 응답 모양은 상품 팀 회신 전까지 요청서(설계 8장) 기준 가정이다.
 * 이름에 Refund를 붙인 이유: 주문 모듈에 {@code ProductApiClient} 빈이 있어 monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 */
@Component
public class RefundProductApiClient implements RefundProductClient {

    private final RestClient restClient;

    public RefundProductApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/products", RefundErrorCode.PRODUCT_API_FAILED);
    }

    @Override
    public void releaseStock(Long refundId, Long orderId, Long productOptionId, int quantity) {
        restClient.post()
                .uri("/stock-allocations/{orderId}/partial-release", orderId)
                .body(new StockPartialReleaseRequest(refundId, productOptionId, quantity))
                .retrieve()
                .toBodilessEntity();
    }
}
