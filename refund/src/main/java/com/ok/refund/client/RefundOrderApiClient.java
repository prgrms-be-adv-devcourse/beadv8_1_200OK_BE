package com.ok.refund.client;

import com.ok.common.exception.RestApiException;
import com.ok.refund.client.dto.OrderRefundApplyRequest;
import com.ok.refund.client.dto.RefundOrderItemResponse;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.RefundOrderClient;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.refund.service.dto.SellerGroupItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 주문 모듈 내부 API를 HTTP로 부른다. 경로와 응답 모양은 주문 팀 회신 전까지 요청서(설계 9장) 기준 가정이다.
 * 이름에 Refund를 붙인 이유: 다른 모듈에 {@code OrderApiClient} 빈이 있어 monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 */
@Component
public class RefundOrderApiClient implements RefundOrderClient {

    private final RestClient restClient;

    public RefundOrderApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/orders", RefundErrorCode.ORDER_API_FAILED);
    }

    @Override
    public RefundableOrderItem getOrderItem(Long orderId, Long orderItemId) {
        RefundOrderItemResponse response = restClient.get()
                .uri("/{orderId}/items/{orderItemId}", orderId, orderItemId)
                .retrieve()
                // 주문이 없거나 주문 안에 그 상품이 없음. 남의 주문인지는 환불이 buyerId로 따로 확인한다
                .onStatus(status -> status.value() == 404, (request, httpResponse) -> {
                    throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_FOUND);
                })
                .body(RefundOrderItemResponse.class);
        if (response == null || response.sellerGroup() == null) {
            throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        }
        return toOrderItem(response);
    }

    @Override
    public void applyRefund(Long refundId, Long orderItemId, int quantity) {
        restClient.post()
                .uri("/items/{orderItemId}/refunds", orderItemId)
                .body(new OrderRefundApplyRequest(refundId, quantity))
                .retrieve()
                .toBodilessEntity();
    }

    private static RefundableOrderItem toOrderItem(RefundOrderItemResponse response) {
        List<SellerGroupItem> items = new ArrayList<>();
        if (response.sellerGroup().items() != null) {
            for (RefundOrderItemResponse.Item item : response.sellerGroup().items()) {
                items.add(new SellerGroupItem(item.orderItemId(), item.unitPrice(), item.quantity()));
            }
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
                response.quantity(),
                response.sellerGroup().shippingFee(),
                response.sellerGroup().remoteAreaFee(),
                response.sellerGroup().baseShippingFee(),
                response.sellerGroup().freeShippingThreshold(),
                items);
    }
}
