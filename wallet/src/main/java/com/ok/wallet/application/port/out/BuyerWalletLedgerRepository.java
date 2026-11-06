package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.BuyerWalletLedger;

/**
 * 구매자 지갑 원장 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface BuyerWalletLedgerRepository {
    BuyerWalletLedger save(BuyerWalletLedger ledger);

    // 같은 지갑에 이미 반영한 근거 키인지 확인한다.
    // 다른 지갑에 같은 키가 있는 경우는 여기서 넘기지 않고, 저장 시 유니크 제약으로 실패하게 둔다.
    boolean existsByBuyerWalletIdAndReferenceKey(Long buyerWalletId, String referenceKey);
}
