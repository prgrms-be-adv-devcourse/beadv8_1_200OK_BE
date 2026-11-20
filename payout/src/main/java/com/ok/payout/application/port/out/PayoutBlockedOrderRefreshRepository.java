package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutBlockedOrderRefresh;

import java.util.Optional;

/**
 * 막힌 주문 집계 표의 갱신 상태(#582) 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutBlockedOrderRefreshRepository {

    // 한 줄뿐인 갱신 상태는 PayoutBlockedOrderRefresh.SINGLE_ROW_ID로 찾음 (마이그레이션이 넣어 둠)
    Optional<PayoutBlockedOrderRefresh> findById(Long id);
}
