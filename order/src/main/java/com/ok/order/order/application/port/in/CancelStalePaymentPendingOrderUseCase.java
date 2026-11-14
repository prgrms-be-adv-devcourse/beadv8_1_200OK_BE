package com.ok.order.order.application.port.in;

import java.util.List;

/**
 * 결제 대기로 너무 오래 남은 주문을 취소하고 선점한 재고를 푼다(#402). 스케줄러가 쓴다.
 */
public interface CancelStalePaymentPendingOrderUseCase {

    /**
     * 정리 대상 주문 ID를 조회한다.
     */
    List<Long> findStalePaymentPendingOrderIds();

    /**
     * 주문 하나를 취소하고 재고를 푼다. 주문마다 트랜잭션을 나눠, 한 주문이 실패해도 다른 주문은 진행한다.
     * 조회 뒤 결제 결과가 먼저 도착했으면 취소하지 않는다.
     *
     * @return 취소했으면 {@code true}
     */
    boolean cancelStalePaymentPendingOrder(Long orderId);
}
