package com.ok.order.order.client;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.client.dto.WalletBalanceResponse;
import com.ok.order.order.exception.OrderErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WalletApiClient {

    private final RestClient restClient;

    public WalletApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/wallets", OrderErrorCode.WALLET_API_FAILED);
    }

    public WalletBalanceResponse getBalance(Long memberId) {
        WalletBalanceResponse response = restClient.get()
                .uri("/buyers/{memberId}/balance", memberId)
                .retrieve()
                .body(WalletBalanceResponse.class);

        if (response == null) {
            throw new RestApiException(OrderErrorCode.WALLET_API_FAILED);
        }
        return response;
    }
}
