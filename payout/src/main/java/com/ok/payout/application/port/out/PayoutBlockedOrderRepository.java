package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutBlockedOrder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 막힌 주문 집계 표(#582) 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutBlockedOrderRepository {

    // 지금 표에 있는 막힌 주문 ID 전부. 막힌 주문 수만큼이라 실패 기록 전체보다 훨씬 작음
    List<Long> findAllOrderIds();

    List<PayoutBlockedOrder> findAllByOrderIdIn(Collection<Long> orderIds);

    Optional<PayoutBlockedOrder> findByOrderId(Long orderId);

    <S extends PayoutBlockedOrder> List<S> saveAll(Iterable<S> blockedOrders);

    // 원인(PayoutBlockedOrderCauseRepository)을 먼저 지운 뒤 부름: 원인이 막힌 주문을 외래키로 가리킴
    void deleteAllByOrderIdIn(Collection<Long> orderIds);
}
