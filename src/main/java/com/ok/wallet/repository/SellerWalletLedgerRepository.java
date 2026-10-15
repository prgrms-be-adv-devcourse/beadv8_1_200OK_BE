package com.ok.wallet.repository;

import com.ok.wallet.domain.SellerWalletLedger;
import org.springframework.data.repository.Repository;

// 원장은 추가 전용이라 JpaRepository 대신 필요한 메서드만 노출해 수정·삭제 메서드를 두지 않는다.
public interface SellerWalletLedgerRepository extends Repository<SellerWalletLedger, Long> {
    SellerWalletLedger save(SellerWalletLedger ledger);

    // 같은 지갑에 이미 반영한 근거 키인지 확인한다.
    // 다른 지갑에 같은 키가 있는 경우는 여기서 넘기지 않고, 저장 시 유니크 제약으로 실패하게 둔다.
    boolean existsBySellerWalletIdAndReferenceKey(Long sellerWalletId, String referenceKey);
}
