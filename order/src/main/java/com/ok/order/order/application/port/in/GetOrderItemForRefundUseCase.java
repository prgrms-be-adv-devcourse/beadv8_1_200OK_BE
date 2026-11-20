package com.ok.order.order.application.port.in;

import com.ok.order.order.application.dto.OrderItemForRefundResult;

/**
 * 환불 모듈이 환불 요청·승인 때 확인할 주문 상품 정보를 조회한다.
 * 읽기만 하고 주문 상품 상태는 바꾸지 않는다(환불과 주문은 환불 때문에 주문 상태를 바꾸지 않기로 했다).
 */
public interface GetOrderItemForRefundUseCase {

    /**
     * 주문 상품과 그 상품이 속한 판매자 묶음의 배송비 값을 조회한다.
     *
     * @throws com.ok.common.exception.RestApiException 주문이 없으면 {@code ORDER_NOT_FOUND},
     *                                                  그 주문에 해당 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND}
     */
    OrderItemForRefundResult getOrderItemForRefund(Long orderId, Long orderItemId);
}
