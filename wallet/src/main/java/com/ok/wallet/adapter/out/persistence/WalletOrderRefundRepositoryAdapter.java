package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.WalletOrderRefundRepository;
import com.ok.wallet.domain.WalletOrderRefund;
import com.ok.wallet.domain.WalletOrderRefundRevert;
import com.ok.wallet.domain.WalletOrderRefundRevertStatus;
import com.ok.wallet.domain.WalletOrderRefundStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 출력 포트 {@link WalletOrderRefundRepository}를 {@link WalletOrderRefundJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class WalletOrderRefundRepositoryAdapter implements WalletOrderRefundRepository {

    private final WalletOrderRefundJpaRepository walletOrderRefundJpaRepository;
    private final WalletOrderRefundRevertJpaRepository walletOrderRefundRevertJpaRepository;

    @Override
    public int insertIfAbsent(Long refundId, Long orderId, Long buyerId, long amount, String status,
                              String failureReason, LocalDateTime now) {
        return walletOrderRefundJpaRepository.insertIfAbsent(
                refundId, orderId, buyerId, amount, status, failureReason, now);
    }

    @Override
    public Optional<WalletOrderRefund> findByRefundId(Long refundId) {
        return walletOrderRefundJpaRepository.findByRefundId(refundId);
    }

    @Override
    public List<WalletOrderRefund> findAllByRefundIdIn(Collection<Long> refundIds) {
        return walletOrderRefundJpaRepository.findByRefundIdIn(refundIds);
    }

    @Override
    public Optional<WalletOrderRefund> findForShareByRefundId(Long refundId) {
        return walletOrderRefundJpaRepository.findForShareByRefundId(refundId);
    }

    @Override
    public long sumCompletedAmountForShareByOrderId(Long orderId) {
        Set<Long> revertedRefundIds = walletOrderRefundRevertJpaRepository
                .findForShareByOrderIdAndStatus(orderId, WalletOrderRefundRevertStatus.REVERTED).stream()
                .map(WalletOrderRefundRevert::getRefundId)
                .collect(Collectors.toSet());
        return walletOrderRefundJpaRepository
                .findForShareByOrderIdAndStatus(orderId, WalletOrderRefundStatus.COMPLETED).stream()
                .filter(refund -> !revertedRefundIds.contains(refund.getRefundId()))
                .mapToLong(WalletOrderRefund::getAmount)
                .sum();
    }
}
