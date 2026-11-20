package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutWalletDeduction;

import java.util.Optional;

/**
 * 지갑 차감 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutWalletDeductionRepository {

    // 지갑 차감 이벤트 중복 확인용 (주문당 1건)
    // 있는지만이 아니라 저장된 금액도 봐야 해서(#391 금액 충돌 기록) 존재 확인 대신 기록 자체를 돌려줌
    Optional<PayoutWalletDeduction> findByOrderId(Long orderId);

    <S extends PayoutWalletDeduction> S save(S deduction);
}
