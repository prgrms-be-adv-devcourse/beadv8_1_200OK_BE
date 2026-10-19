package com.ok.order.order.client;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.client.dto.RemoteAreaFeeResponse;
import com.ok.order.order.exception.OrderErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DeliveryApiClient {

    private final RestClient restClient;

    public DeliveryApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/delivery", OrderErrorCode.DELIVERY_API_FAILED);
    }

    public RemoteAreaFeeResponse getRemoteAreaFee(String zipCode) {
        RemoteAreaFeeResponse response = restClient.get()
                .uri("/remote-area-fee?zipCode={zipCode}", zipCode)
                .retrieve()
                .body(RemoteAreaFeeResponse.class);

        if (response == null) {
            throw new RestApiException(OrderErrorCode.DELIVERY_API_FAILED);
        }
        return response;
    }
}
