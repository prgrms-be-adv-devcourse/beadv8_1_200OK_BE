package com.ok.order.order.application.port.in;

/**
 * 지갑 결제 완료를 주문에 반영한다.
 */
public interface CompletePaymentUseCase {

    /**
     * 결제 대기 주문을 결제 완료로 바꾸고, 장바구니에서 주문한 상품을 지우고, 배송용 이벤트를 발행한다.
     * 이미 처리된 주문이면 로그만 남긴다.
     */
    void completePayment(Long orderId);
}
