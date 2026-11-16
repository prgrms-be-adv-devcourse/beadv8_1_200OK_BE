package com.ok.order.order.application.port.in;

import java.util.List;

/**
 * 결제를 요청하고 오래 결과가 없는 주문의 결제 만료를 지갑에 요청한다(#656). 정리 스케줄러가 쓴다.
 * 주문은 취소하지 않고 결제 대기로 둔다. 돈이 움직였는지는 지갑만 알아서, 주문은 지갑이 보낸 결제 완료·실패로만 끝낸다.
 */
public interface RequestPaymentExpireUseCase {

    /**
     * 결제 만료를 요청할 주문 ID를 조회한다. 결과가 올 때까지 매번 다시 대상이 된다.
     */
    List<Long> findPaymentExpireDueOrderIds();

    /**
     * 주문 하나의 결제 만료를 요청한다. 주문마다 트랜잭션을 나눠, 한 주문이 실패해도 다른 주문은 진행한다.
     * 조회 뒤 결제 결과가 먼저 도착했으면 요청하지 않는다.
     *
     * @return 요청했으면 {@code true}
     */
    boolean requestPaymentExpire(Long orderId);
}
