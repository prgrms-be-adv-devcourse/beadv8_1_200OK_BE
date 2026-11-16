package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.dto.SellerLedgerDepositResult;
import com.ok.wallet.application.port.out.SellerWalletLedgerRepository;
import com.ok.wallet.domain.SellerWalletLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

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

    @Override
    public List<SellerLedgerDepositResult> findDepositsByReferenceKeys(Collection<String> referenceKeys) {
        // 빈 목록이면 DB에 갈 필요가 없고, 빈 IN 조건의 SQL 처리가 DB마다 달라 여기서 끝낸다
        if (referenceKeys.isEmpty()) {
            return List.of();
        }
        return sellerWalletLedgerJpaRepository.findDepositsByReferenceKeys(referenceKeys);
    }
}
