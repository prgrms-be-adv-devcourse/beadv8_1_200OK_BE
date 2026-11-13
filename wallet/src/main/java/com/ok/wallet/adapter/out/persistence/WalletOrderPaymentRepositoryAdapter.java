package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.WalletOrderPaymentRepository;
import com.ok.wallet.domain.WalletOrderPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 출력 포트 {@link WalletOrderPaymentRepository}를 {@link WalletOrderPaymentJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class WalletOrderPaymentRepositoryAdapter implements WalletOrderPaymentRepository {

    private final WalletOrderPaymentJpaRepository walletOrderPaymentJpaRepository;

    @Override
    public int insertIfAbsent(Long orderId, Long buyerId, long amount, String status, LocalDateTime now) {
        return walletOrderPaymentJpaRepository.insertIfAbsent(orderId, buyerId, amount, status, now);
    }

    @Override
    public Optional<WalletOrderPayment> findForShareByOrderId(Long orderId) {
        return walletOrderPaymentJpaRepository.findForShareByOrderId(orderId);
    }
}
