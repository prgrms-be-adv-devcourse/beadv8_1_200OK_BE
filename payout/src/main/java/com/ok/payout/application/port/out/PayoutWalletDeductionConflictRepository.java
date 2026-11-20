package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutWalletDeductionConflict;

/**
 * 지갑 차감 금액 충돌 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutWalletDeductionConflictRepository {

    // 같은 충돌(주문, 받은 금액)이 이미 기록됐는지 (Kafka 재전달 시 중복 저장 방지)
    boolean existsByOrderIdAndReceivedAmount(Long orderId, Long receivedAmount);

    <S extends PayoutWalletDeductionConflict> S save(S conflict);
}
