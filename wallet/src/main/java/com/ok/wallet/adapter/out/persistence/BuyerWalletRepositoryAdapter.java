package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.BuyerWalletRepository;
import com.ok.wallet.domain.BuyerWallet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 출력 포트 {@link BuyerWalletRepository}를 {@link BuyerWalletJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class BuyerWalletRepositoryAdapter implements BuyerWalletRepository {

    private final BuyerWalletJpaRepository buyerWalletJpaRepository;

    @Override
    public boolean existsByMemberId(Long memberId) {
        return buyerWalletJpaRepository.existsByMemberId(memberId);
    }

    @Override
    public void insertIfAbsent(Long memberId, LocalDateTime now) {
        buyerWalletJpaRepository.insertIfAbsent(memberId, now);
    }

    @Override
    public Optional<BuyerWallet> findForUpdateByMemberId(Long memberId) {
        return buyerWalletJpaRepository.findForUpdateByMemberId(memberId);
    }

    @Override
    public Optional<BuyerWallet> findByMemberId(Long memberId) {
        return buyerWalletJpaRepository.findByMemberId(memberId);
    }
}
