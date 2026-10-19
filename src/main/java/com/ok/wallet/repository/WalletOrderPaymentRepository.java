package com.ok.wallet.repository;

import com.ok.wallet.domain.WalletOrderPayment;
import org.springframework.data.repository.Repository;

import java.util.Optional;

// 결제 결과는 한 번 기록하면 바뀌지 않으므로 저장과 조회만 둔다.
public interface WalletOrderPaymentRepository extends Repository<WalletOrderPayment, Long> {
    WalletOrderPayment save(WalletOrderPayment walletOrderPayment);

    Optional<WalletOrderPayment> findByOrderId(Long orderId);
}
