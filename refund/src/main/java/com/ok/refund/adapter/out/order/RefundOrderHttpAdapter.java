package com.ok.refund.adapter.out.order;

import com.ok.common.exception.RestApiException;
import com.ok.refund.adapter.out.order.dto.RefundOrderCancellationRequest;
import com.ok.refund.adapter.out.order.dto.RefundOrderItemResponse;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.application.port.out.OrderCancellation;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.application.port.out.OrderQueryPort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 주문 모듈 내부 API를 HTTP로 부른다. 경로와 응답 모양은 주문 팀 API가 나오기 전까지 요청서 기준 가정이다.
 * 이름에 Refund를 붙인 이유: 다른 모듈에도 주문 호출 클라이언트가 있어, monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 * 조회(①)와 취소 반영(②)은 포트를 나눴지만 같은 주문 내부 API라 주소·타임아웃·실패 처리를 함께 쓰려고 한 어댑터가 둘 다 구현한다.
 */
@Component
public class RefundOrderHttpAdapter implements OrderQueryPort, OrderCancellationPort {

    private final RestClient restClient;

    // 주소 설정은 주문 모듈의 클라이언트와 같은 키·기본값을 쓴다. 한 앱(monolith)에서는 자기 자신을 부르게 된다
    public RefundOrderHttpAdapter(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
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
        // 묶음이 없으면 배송비를 판단할 수 없다. 0원으로 넘기면 돌려줄 배송비를 조용히 빠뜨리므로 본문 누락과 같게 500으로 드러낸다
        if (response.sellerGroup() == null) {
            throw new IllegalStateException("주문 상품 조회 응답에 판매자 묶음이 없습니다. orderId=" + orderId + ", orderItemId=" + orderItemId);
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
                toBundle(response.sellerGroup()));
    }

    // 요청서 ②: POST /internal/v1/orders/items/{orderItemId}/refunds. 성공 상태 코드는 요청서에 없어 2xx 전부를 성공으로 본다(주문 #28 구현 때 확인).
    // 실패(연결 실패·타임아웃·4xx·5xx)는 InternalRestClient가 모두 ORDER_API_FAILED로 바꾼다. 404·409(합계 초과, 같은 refundId인데 수량이 다름)를
    // 일시적 실패와 나누지 않는 이유: 성공 흐름만 만드는 범위라 구분해도 다르게 처리할 길이 아직 없다(#516에서 구분)
    @Override
    public void reflectCancellation(OrderCancellation cancellation) {
        restClient.post()
                .uri("/items/{orderItemId}/refunds", cancellation.orderItemId())
                .body(RefundOrderCancellationRequest.from(cancellation))
                .retrieve()
                .toBodilessEntity();
    }

    // 값 검사(음수, 빈 목록, 중복 상품)는 BundleShipping·BundleItem 생성자가 한다
    private static BundleShipping toBundle(RefundOrderItemResponse.SellerGroup sellerGroup) {
        if (sellerGroup.items() == null) {
            throw new IllegalStateException("주문 상품 조회 응답의 판매자 묶음에 상품 목록이 없습니다.");
        }
        List<BundleItem> items = new ArrayList<>();
        for (RefundOrderItemResponse.Item item : sellerGroup.items()) {
            if (item == null) {
                throw new IllegalStateException("주문 상품 조회 응답의 판매자 묶음 상품 목록에 빈 값이 있습니다.");
            }
            items.add(new BundleItem(item.orderItemId(), item.unitPrice(), item.quantity()));
        }
        return new BundleShipping(sellerGroup.shippingFee(), sellerGroup.remoteAreaFee(), sellerGroup.baseShippingFee(),
                sellerGroup.freeShippingThreshold(), items);
    }
}
