package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutWalletDeduction;

/**
 * 지갑 차감 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutWalletDeductionRepository {

    // 지갑 차감 이벤트 중복 확인용 (주문당 1건)
    boolean existsByOrderId(Long orderId);

    <S extends PayoutWalletDeduction> S save(S deduction);
}
