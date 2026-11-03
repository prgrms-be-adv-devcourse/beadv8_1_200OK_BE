package com.ok.refund.client;

import com.ok.common.exception.RestApiException;
import com.ok.refund.client.dto.RefundOrderItemResponse;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.RefundOrderClient;
import com.ok.refund.service.dto.RefundableOrderItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 주문 모듈 내부 API를 HTTP로 부른다. 경로와 응답 모양은 주문 팀 API가 나오기 전까지 요청서 기준 가정이다.
 * 이름에 Refund를 붙인 이유: 다른 모듈에도 주문 호출 클라이언트가 있어, monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 */
@Component
public class RefundOrderApiClient implements RefundOrderClient {

    private final RestClient restClient;

    // 주소 설정은 주문 모듈의 클라이언트와 같은 키·기본값을 쓴다. 한 앱(monolith)에서는 자기 자신을 부르게 된다
    public RefundOrderApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/orders", RefundErrorCode.ORDER_API_FAILED);
    }

    @Override
    public RefundableOrderItem getOrderItem(Long orderId, Long orderItemId) {
        RefundOrderItemResponse response = restClient.get()
                .uri("/{orderId}/items/{orderItemId}", orderId, orderItemId)
                .retrieve()
                // 주문이 없거나 그 주문 안에 상품이 없음. 남의 주문인지는 환불이 buyerId로 따로 확인한다
                .onStatus(status -> status.value() == 404, (request, httpResponse) -> {
                    throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_FOUND);
                })
                .body(RefundOrderItemResponse.class);
        // 200인데 본문이 비었으면 다시 시도해도 낫지 않는 주문 쪽 버그다. 503(잠시 후 다시 시도)이 아니라 500으로 드러내
        // 장애로 잡히게 한다(exception.md). 본문이 깨졌거나 칸이 빠진 경우도 .body()에서 RestClientException이 나 같은 500이다
        if (response == null) {
            throw new IllegalStateException("주문 상품 조회 응답 본문이 비었습니다. orderId=" + orderId + ", orderItemId=" + orderItemId);
        }
        return new RefundableOrderItem(
                response.orderId(),
                response.orderItemId(),
                response.buyerId(),
                response.sellerId(),
                response.productOptionId(),
                response.productName(),
                response.optionName(),
                response.status(),
                response.unitPrice(),
                response.quantity());
    }
}
