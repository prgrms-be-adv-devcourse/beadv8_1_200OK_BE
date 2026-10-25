package com.ok.payout.repository;

import com.ok.payout.domain.PayoutWalletDeduction;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface PayoutWalletDeductionRepository extends Repository<PayoutWalletDeduction, Long> {

    // 지갑 차감 이벤트 중복 확인용 (주문당 1건)
    boolean existsByOrderId(Long orderId);

    PayoutWalletDeduction save(PayoutWalletDeduction deduction);

    Optional<PayoutWalletDeduction> findByOrderId(Long orderId);
}
