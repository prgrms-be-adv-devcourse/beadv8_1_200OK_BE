package com.ok.order.order.client;


import com.ok.common.exception.RestApiException;
import com.ok.order.order.client.dto.ProductOptionInfoResponse;
import com.ok.order.order.client.dto.StockAllocationItemRequest;
import com.ok.order.order.client.dto.StockAllocationRequest;
import com.ok.order.order.exception.OrderErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class ProductApiClient {

    private final RestClient restClient;

    public ProductApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl + "/internal/v1/products")
                .build();
    }

    // 상품 옵션 ID ','로 나눈 리스트로 변경 후 상품 모듈 API 호출
    public List<ProductOptionInfoResponse> getOptions(List<Long> optionIds) {
        List<String> idTexts = new ArrayList<>();
        for (Long optionId : optionIds) {
            idTexts.add(optionId.toString());
        }
        String ids = String.join(",", idTexts);

        ProductOptionInfoResponse[] response = restClient.get()
                .uri("/options?optionIds={ids}", ids)
                .retrieve()
                .body(ProductOptionInfoResponse[].class);

        if (response == null) {
            return List.of();
        }
        return List.of(response);
    }

    // 재고 선점 (전부 성공 or 전부 실패, 같은 주문 ID 재요청은 무시)
    public void allocateStock(Long orderId, List<StockAllocationItemRequest> items) {
        restClient.post()
                .uri("/stock-allocations")
                .body(new StockAllocationRequest(orderId, items))
                .retrieve()
                // 없는 옵션일 경우
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new RestApiException(OrderErrorCode.PRODUCT_OPTION_NOT_FOUND);
                })
                // 판매 불가, 재고 부족, 동시 요청, 해제된 주문일 경우 (PROBLEM_BASE_URI로 type 구분)
                .onStatus(status -> status.value() == 409, (request, response) -> {
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (body.contains("option-not-purchasable")) {
                        throw new RestApiException(OrderErrorCode.PRODUCT_NOT_ON_SALE);
                    }
                    if (body.contains("insufficient-stock")) {
                        throw new RestApiException(OrderErrorCode.OUT_OF_STOCK);
                    }
                    throw new RestApiException(OrderErrorCode.STOCK_ALLOCATION_FAILED);
                })
                .toBodilessEntity();

    }

    // 재고 선점 해제
    public void releaseStock(Long orderId) {
        restClient.post()
                .uri("/stock-allocations/{orderId}/release", orderId)
                .retrieve()
                .toBodilessEntity();
    }
}
