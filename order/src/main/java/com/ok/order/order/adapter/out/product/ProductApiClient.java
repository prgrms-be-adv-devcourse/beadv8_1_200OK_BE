package com.ok.order.order.adapter.out.product;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.adapter.out.http.InternalRestClient;
import com.ok.order.order.adapter.out.product.dto.ProductOptionInfoResponse;
import com.ok.order.order.adapter.out.product.dto.StockAllocationItemRequest;
import com.ok.order.order.adapter.out.product.dto.StockAllocationRequest;
import com.ok.order.order.application.dto.ProductOption;
import com.ok.order.order.application.dto.StockItem;
import com.ok.order.order.application.port.out.ProductPort;
import com.ok.order.order.exception.OrderErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 상품 모듈 내부 API를 HTTP로 부르는 {@link ProductPort} 구현. 상품 응답 모양은 이 클래스까지만 쓰고 주문 DTO로 바꿔 넘긴다.
 */
@Component
public class ProductApiClient implements ProductPort {

    private final RestClient restClient;

    public ProductApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/products", OrderErrorCode.PRODUCT_API_FAILED);
    }

    // 상품 옵션 ID ','로 나눈 리스트로 변경 후 상품 모듈 API 호출
    @Override
    public List<ProductOption> findOptions(List<Long> optionIds) {
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
        List<ProductOption> options = new ArrayList<>();
        for (ProductOptionInfoResponse option : response) {
            options.add(option.toProductOption());
        }
        return options;
    }

    // 재고 선점 (전부 성공 or 전부 실패, 같은 주문 ID 재요청은 무시)
    @Override
    public void allocateStock(Long orderId, List<StockItem> items) {
        List<StockAllocationItemRequest> requestItems = new ArrayList<>();
        for (StockItem item : items) {
            requestItems.add(new StockAllocationItemRequest(item.optionId(), item.quantity()));
        }
        restClient.post()
                .uri("/stock-allocations")
                .body(new StockAllocationRequest(orderId, requestItems))
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
    @Override
    public void releaseStock(Long orderId) {
        restClient.post()
                .uri("/stock-allocations/{orderId}/release", orderId)
                .retrieve()
                .toBodilessEntity();
    }
}
