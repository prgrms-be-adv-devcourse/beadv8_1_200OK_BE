package com.ok.order.order.adapter.out.wallet;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.adapter.out.http.InternalRestClient;
import com.ok.order.order.adapter.out.wallet.dto.WalletBalanceResponse;
import com.ok.order.order.application.port.out.WalletPort;
import com.ok.order.order.exception.OrderErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 지갑 모듈 내부 API를 HTTP로 부르는 {@link WalletPort} 구현.
 */
@Component
public class WalletApiClient implements WalletPort {

    private final RestClient restClient;

    public WalletApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/wallets", OrderErrorCode.WALLET_API_FAILED);
    }

    @Override
    public long getBalance(Long memberId) {
        WalletBalanceResponse response = restClient.get()
                .uri("/buyers/{memberId}/balance", memberId)
                .retrieve()
                .body(WalletBalanceResponse.class);

        if (response == null) {
            throw new RestApiException(OrderErrorCode.WALLET_API_FAILED);
        }
        return response.balance();
    }
}
