package com.ok.order.order.client;

import com.ok.order.order.client.dto.RemoteAreaFeeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DeliveryApiClient {

    private final RestClient restClient;

    public DeliveryApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl + "/internal/v1/delivery") // TODO 배송 모듈 도서산간 운임 API 확정되면 경로 맞추기
                .build();
    }

    public RemoteAreaFeeResponse getRemoteAreaFee(String zipCode) {
        return restClient.get()
                .uri("/remote-area-fee?zipCode={zipCode}", zipCode)
                .retrieve()
                .body(RemoteAreaFeeResponse.class);
    }
}
