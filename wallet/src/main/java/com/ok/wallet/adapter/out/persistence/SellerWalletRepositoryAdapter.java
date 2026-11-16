package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.SellerWalletRepository;
import com.ok.wallet.domain.SellerWallet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 출력 포트 {@link SellerWalletRepository}를 {@link SellerWalletJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class SellerWalletRepositoryAdapter implements SellerWalletRepository {

    private final SellerWalletJpaRepository sellerWalletJpaRepository;

    @Override
    public boolean existsByMemberId(Long memberId) {
        return sellerWalletJpaRepository.existsByMemberId(memberId);
    }

    @Override
    public Optional<SellerWallet> findByMemberId(Long memberId) {
        return sellerWalletJpaRepository.findByMemberId(memberId);
    }

    @Override
    public void insertIfAbsent(Long memberId, LocalDateTime now) {
        sellerWalletJpaRepository.insertIfAbsent(memberId, now);
    }

    @Override
    public Optional<SellerWallet> findForUpdateByMemberId(Long memberId) {
        return sellerWalletJpaRepository.findForUpdateByMemberId(memberId);
    }
}
