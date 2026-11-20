package com.ok.wallet.application.port.out;

import com.ok.wallet.application.dto.SellerLedgerDepositResult;
import com.ok.wallet.domain.SellerWalletLedger;

import java.util.Collection;
import java.util.List;

/**
 * 판매자 지갑 원장 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface SellerWalletLedgerRepository {
    SellerWalletLedger save(SellerWalletLedger ledger);

    // 같은 지갑에 이미 반영한 근거 키인지 확인한다.
    // 다른 지갑에 같은 키가 있는 경우는 여기서 넘기지 않고, 저장 시 유니크 제약으로 실패하게 둔다.
    boolean existsBySellerWalletIdAndReferenceKey(Long sellerWalletId, String referenceKey);

    /**
     * 근거 키 목록에 해당하는 원장을 지갑 회원 ID와 함께 한 번의 조회로 읽는다.
     * 원장에 없는 키는 결과에 없다. 근거 키는 표 전체에서 유일하므로 키마다 많아야 한 건이다.
     */
    List<SellerLedgerDepositResult> findDepositsByReferenceKeys(Collection<String> referenceKeys);
}
