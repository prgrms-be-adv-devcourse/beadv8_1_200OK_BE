package com.ok.order.order.client;

import com.ok.order.order.client.dto.WalletBalanceResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WalletApiClient {

    private final RestClient restClient;

    public WalletApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl + "/internal/v1/wallets")
                .build();
    }

    public WalletBalanceResponse getBalance(Long memberId) {
        return restClient.get()
                .uri("/buyers/{memberId}/balance", memberId)
                .retrieve()
                .body(WalletBalanceResponse.class);
    }
}
