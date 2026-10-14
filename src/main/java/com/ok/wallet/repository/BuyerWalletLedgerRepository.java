package com.ok.wallet.repository;

import com.ok.wallet.domain.BuyerWalletLedger;
import org.springframework.data.repository.Repository;

// 원장은 추가 전용이라 JpaRepository 대신 필요한 메서드만 노출해 수정·삭제 메서드를 두지 않는다.
public interface BuyerWalletLedgerRepository extends Repository<BuyerWalletLedger, Long> {
    BuyerWalletLedger save(BuyerWalletLedger ledger);

    BuyerWalletLedger saveAndFlush(BuyerWalletLedger ledger);

    boolean existsByReferenceKey(String referenceKey);
}
