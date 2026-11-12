package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.SellerWalletLedgerRepository;
import com.ok.wallet.domain.SellerWalletLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 출력 포트 {@link SellerWalletLedgerRepository}를 {@link SellerWalletLedgerJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class SellerWalletLedgerRepositoryAdapter implements SellerWalletLedgerRepository {

    private final SellerWalletLedgerJpaRepository sellerWalletLedgerJpaRepository;

    @Override
    public SellerWalletLedger save(SellerWalletLedger ledger) {
        return sellerWalletLedgerJpaRepository.save(ledger);
    }

    @Override
    public boolean existsBySellerWalletIdAndReferenceKey(Long sellerWalletId, String referenceKey) {
        return sellerWalletLedgerJpaRepository.existsBySellerWalletIdAndReferenceKey(sellerWalletId, referenceKey);
    }
}
