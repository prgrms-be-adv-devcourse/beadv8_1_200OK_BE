package com.ok.refund.adapter.out.order;

import com.ok.common.exception.RestApiException;
import com.ok.refund.adapter.out.order.dto.RefundOrderCancellationRequest;
import com.ok.refund.adapter.out.order.dto.RefundOrderItemResponse;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.application.port.out.OrderCancellation;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.application.port.out.OrderCancellationResult;
import com.ok.refund.application.port.out.OrderQueryPort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 주문 모듈 내부 API를 HTTP로 부른다. 조회(①)는 #533, 취소 반영(②)은 #542로 머지된 실제 API와 경로·응답 칸을 맞춰 확인했다.
 * 이름에 Refund를 붙인 이유: 다른 모듈에도 주문 호출 클라이언트가 있어, monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 * 조회(①)와 취소 반영(②)은 포트를 나눴지만 같은 주문 내부 API라 주소·타임아웃·실패 처리를 함께 쓰려고 한 어댑터가 둘 다 구현한다.
 */
@Component
public class RefundOrderHttpAdapter implements OrderQueryPort, OrderCancellationPort {

    // 409인데 주문이 설명 문장을 주지 않았을 때 채우는 문장. 거절 결과의 설명은 비우지 않는다
    private static final String NO_ORDER_DETAIL = "주문 응답에 설명 없음";

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

    // 주문 ②(#542 실제 API): POST /internal/v1/orders/items/{orderItemId}/refunds, 본문 refundId·quantity·deductedShippingFee, 성공 204.
    // 2xx는 전부 반영됨으로 본다. 409는 거절됨, 그 밖의 오류 상태와 응답 없음(연결 실패·타임아웃)은 ORDER_API_FAILED다.
    // 409를 이유와 상관없이 전부 거절로 보는 이유: 주문은 409를 세 가지(이미 출고됨, 수량 초과, 같은 refundId인데 내용이 다름)에 쓰는데
    // 이유 이름은 ProblemDetail의 type에만 오고, type은 PROBLEM_BASE_URI 설정이 기본값이면 about:blank라 환불이 이유를 가를 수 없다(11/13 결정).
    // 어느 이유든 같은 요청을 다시 보내도 낫지 않으므로 다시 시도 대상이 아니라 거절로 끝내는 것이 맞다.
    // detail 문장은 로그용으로만 돌려준다. 문구는 바뀔 수 있어 판단에 쓰지 않고 DB에도 남기지 않는다.
    // exchange를 쓰는 이유: retrieve()는 오류 상태를 예외로 바꿔 409의 본문을 꺼내기 불편하다. exchange는 기본 상태 처리를 건너뛰므로
    // 오류 상태를 여기서 직접 ORDER_API_FAILED로 바꾼다(응답을 못 받은 IOException은 InternalRestClient의 인터셉터가 같은 코드로 바꾼다)
    @Override
    public OrderCancellationResult reflectCancellation(OrderCancellation cancellation) {
        try {
            return restClient.post()
                    .uri("/items/{orderItemId}/refunds", cancellation.orderItemId())
                    .body(RefundOrderCancellationRequest.from(cancellation))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return OrderCancellationResult.reflected();
                        }
                        if (response.getStatusCode().value() == 409) {
                            return OrderCancellationResult.rejected(readDetail(response));
                        }
                        throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
                    });
        } catch (ResourceAccessException e) {
            // POST는 응답 상태줄을 람다 안의 getStatusCode()에서 처음 읽는다. 그 시점은 InternalRestClient 인터셉터(요청을 보내는 순간만 감쌈) 밖이라
            // 읽기 타임아웃·연결 끊김이 인터셉터 대신 ResourceAccessException으로 올라와 500이 된다. 여기서 같은 코드로 바꿔 503으로 맞춘다.
            // 람다가 던진 RestApiException은 이 예외가 아니라 그대로 올라간다
            throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        }
    }

    // 본문이 비었거나 깨졌거나 detail이 없어도 거절 자체는 유효하다. 설명이 없다고 거절을 실패로 바꾸면 안 되므로 채워서 돌려준다
    private static String readDetail(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            ProblemDetail problem = response.bodyTo(ProblemDetail.class);
            if (problem != null && problem.getDetail() != null && !problem.getDetail().isBlank()) {
                return problem.getDetail();
            }
        } catch (RuntimeException e) {
            // 읽지 못한 본문은 아래 기본 문장으로 대신한다
        }
        return NO_ORDER_DETAIL;
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
