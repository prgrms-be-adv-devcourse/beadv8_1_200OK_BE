package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.WalletOrderRefundRevertRepository;
import com.ok.wallet.domain.WalletOrderRefundRevert;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 출력 포트 {@link WalletOrderRefundRevertRepository}를 {@link WalletOrderRefundRevertJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class WalletOrderRefundRevertRepositoryAdapter implements WalletOrderRefundRevertRepository {

    private final WalletOrderRefundRevertJpaRepository walletOrderRefundRevertJpaRepository;

    @Override
    public int insertIfAbsent(Long refundId, Long orderId, Long buyerId, long amount, String status,
                              String failureReason, LocalDateTime now) {
        return walletOrderRefundRevertJpaRepository.insertIfAbsent(
                refundId, orderId, buyerId, amount, status, failureReason, now);
    }

    @Override
    public Optional<WalletOrderRefundRevert> findByRefundId(Long refundId) {
        return walletOrderRefundRevertJpaRepository.findByRefundId(refundId);
    }

    @Override
    public Optional<WalletOrderRefundRevert> findForShareByRefundId(Long refundId) {
        return walletOrderRefundRevertJpaRepository.findForShareByRefundId(refundId);
    }
}
