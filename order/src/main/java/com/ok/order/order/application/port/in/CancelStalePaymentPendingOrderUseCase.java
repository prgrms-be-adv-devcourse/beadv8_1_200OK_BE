package com.ok.order.order.application.port.in;

import java.util.List;

/**
 * 결제를 요청하기 전에 멈춘 결제 대기 주문을 취소하고 선점한 재고를 푼다(#402). 스케줄러가 쓴다.
 * 지갑이 모르는 주문이라 주문이 혼자 취소해도 된다. 결제를 요청한 주문은 취소하지 않고 결제 만료를 요청한다({@link RequestPaymentExpireUseCase}, #656).
 */
public interface CancelStalePaymentPendingOrderUseCase {

    /**
     * 정리 대상 주문 ID를 조회한다.
     */
    List<Long> findStalePaymentPendingOrderIds();

    /**
     * 주문 하나를 취소하고 재고를 푼다. 주문마다 트랜잭션을 나눠, 한 주문이 실패해도 다른 주문은 진행한다.
     * 조회 뒤 결제를 요청했거나 결제 결과가 먼저 도착했으면 취소하지 않는다.
     *
     * @return 취소했으면 {@code true}
     */
    boolean cancelStalePaymentPendingOrder(Long orderId);
}
