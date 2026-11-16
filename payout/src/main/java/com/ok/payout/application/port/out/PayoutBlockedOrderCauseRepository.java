package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutBlockedOrderCause;

import java.util.Collection;
import java.util.List;

/**
 * 막힌 주문 원인(#582, 막힌 주문 집계 표의 하위 표) 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutBlockedOrderCauseRepository {

    <S extends PayoutBlockedOrderCause> List<S> saveAll(Iterable<S> causes);

    void deleteAllByOrderIdIn(Collection<Long> orderIds);
}
