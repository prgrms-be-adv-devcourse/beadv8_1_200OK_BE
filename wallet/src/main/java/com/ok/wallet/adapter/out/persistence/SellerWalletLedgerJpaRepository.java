package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.dto.SellerLedgerDepositResult;
import com.ok.wallet.domain.SellerWalletLedger;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

// 원장은 추가 전용이라 JpaRepository 대신 필요한 메서드만 노출해 수정·삭제 메서드를 두지 않는다.
public interface SellerWalletLedgerJpaRepository extends Repository<SellerWalletLedger, Long> {

    SellerWalletLedger save(SellerWalletLedger ledger);

    boolean existsBySellerWalletIdAndReferenceKey(Long sellerWalletId, String referenceKey);

    // 원장은 지갑을 연관관계가 아닌 ID로만 가지므로 지갑 엔티티를 ID로 이어 붙여 회원 ID를 같은 조회에서 읽는다
    @Query("""
            select new com.ok.wallet.application.dto.SellerLedgerDepositResult(
                l.referenceKey, w.memberId, l.amount, l.createdAt)
            from SellerWalletLedger l
            join SellerWallet w on w.id = l.sellerWalletId
            where l.referenceKey in :referenceKeys
            """)
    List<SellerLedgerDepositResult> findDepositsByReferenceKeys(@Param("referenceKeys") Collection<String> referenceKeys);
}
