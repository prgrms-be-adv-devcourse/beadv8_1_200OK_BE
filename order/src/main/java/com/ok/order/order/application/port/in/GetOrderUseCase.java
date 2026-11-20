package com.ok.order.order.application.port.in;

import com.ok.order.order.application.dto.OrderDetailResult;
import com.ok.order.order.application.dto.OrderSummaryResult;
import org.springframework.data.domain.Page;

/**
 * 내 주문을 조회한다.
 */
public interface GetOrderUseCase {

    /**
     * 구매자의 주문을 최신순으로 페이지 조회한다.
     */
    Page<OrderSummaryResult> getOrders(Long buyerId, int page, int size);

    /**
     * 구매자의 주문 상세를 조회한다.
     *
     * @throws com.ok.common.exception.RestApiException 주문이 없거나 다른 회원의 주문이면 {@code ORDER_NOT_FOUND}
     */
    OrderDetailResult getOrder(Long buyerId, Long orderId);
}
