package com.ok.order.order.application.port.in;

import java.time.LocalDateTime;

/**
 * 배송 상태를 주문 상품에 반영한다. 배송 모듈 이벤트를 받을 때 쓴다.
 */
public interface UpdateShippingStatusUseCase {

    /**
     * 판매자 묶음의 주문 상품을 배송 중으로 바꾼다.
     */
    void startShipping(Long orderId, Long sellerId);

    /**
     * 판매자 묶음의 주문 상품을 배송 완료로 바꾼다.
     */
    void completeDelivery(Long orderId, Long sellerId, LocalDateTime deliveredAt);
}
