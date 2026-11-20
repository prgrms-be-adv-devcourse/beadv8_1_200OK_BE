package com.ok.order.order.application.port.in;

/**
 * 지갑 결제 실패를 주문에 반영한다.
 */
public interface FailPaymentUseCase {

    /**
     * 결제 대기 주문을 결제 실패로 바꾸고 잡아 둔 재고를 푼다. 장바구니는 그대로 둔다.
     *
     * @param reason 지갑이 알려 준 실패 사유 (로그용)
     */
    void failPayment(Long orderId, String reason);
}
