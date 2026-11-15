package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.WalletOrderRefundRepository;
import com.ok.wallet.domain.WalletOrderRefund;
import com.ok.wallet.domain.WalletOrderRefundStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 출력 포트 {@link WalletOrderRefundRepository}를 {@link WalletOrderRefundJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class WalletOrderRefundRepositoryAdapter implements WalletOrderRefundRepository {

    private final WalletOrderRefundJpaRepository walletOrderRefundJpaRepository;

    @Override
    public int insertIfAbsent(Long refundId, Long orderId, Long buyerId, long amount, String status,
                              String failureReason, LocalDateTime now) {
        return walletOrderRefundJpaRepository.insertIfAbsent(
                refundId, orderId, buyerId, amount, status, failureReason, now);
    }

    @Override
    public Optional<WalletOrderRefund> findForShareByRefundId(Long refundId) {
        return walletOrderRefundJpaRepository.findForShareByRefundId(refundId);
    }

    @Override
    public long sumCompletedAmountForShareByOrderId(Long orderId) {
        return walletOrderRefundJpaRepository
                .findForShareByOrderIdAndStatus(orderId, WalletOrderRefundStatus.COMPLETED).stream()
                .mapToLong(WalletOrderRefund::getAmount)
                .sum();
    }
}
