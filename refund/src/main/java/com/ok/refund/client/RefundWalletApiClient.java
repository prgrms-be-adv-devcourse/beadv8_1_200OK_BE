package com.ok.refund.client;

import com.ok.refund.client.dto.WalletRefundRequest;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.RefundWalletClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 지갑 모듈 내부 API를 HTTP로 부른다. 경로와 응답 모양은 지갑 팀 회신 전까지 요청서(설계 10장) 기준 가정이다.
 * 이름에 Refund를 붙인 이유: 주문 모듈에 {@code WalletApiClient} 빈이 있어 monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 */
@Component
public class RefundWalletApiClient implements RefundWalletClient {

    private final RestClient restClient;

    public RefundWalletApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/wallets", RefundErrorCode.WALLET_API_FAILED);
    }

    @Override
    public void refund(Long refundId, Long orderId, Long buyerId, Long sellerId, long amount) {
        restClient.post()
                .uri("/refunds")
                .body(new WalletRefundRequest(refundId, orderId, buyerId, sellerId, amount))
                .retrieve()
                .toBodilessEntity();
    }
}
