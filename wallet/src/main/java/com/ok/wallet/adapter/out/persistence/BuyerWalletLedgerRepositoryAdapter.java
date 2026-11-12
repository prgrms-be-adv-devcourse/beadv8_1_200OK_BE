package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.BuyerWalletLedgerRepository;
import com.ok.wallet.domain.BuyerWalletLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 출력 포트 {@link BuyerWalletLedgerRepository}를 {@link BuyerWalletLedgerJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class BuyerWalletLedgerRepositoryAdapter implements BuyerWalletLedgerRepository {

    private final BuyerWalletLedgerJpaRepository buyerWalletLedgerJpaRepository;

    @Override
    public BuyerWalletLedger save(BuyerWalletLedger ledger) {
        return buyerWalletLedgerJpaRepository.save(ledger);
    }

    @Override
    public boolean existsByBuyerWalletIdAndReferenceKey(Long buyerWalletId, String referenceKey) {
        return buyerWalletLedgerJpaRepository.existsByBuyerWalletIdAndReferenceKey(buyerWalletId, referenceKey);
    }
}
