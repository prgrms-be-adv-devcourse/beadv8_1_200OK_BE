package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.SellerWalletLedgerRepository;
import com.ok.wallet.domain.SellerWalletLedger;
import org.springframework.data.repository.Repository;

// 원장은 추가 전용이라 JpaRepository 대신 필요한 메서드만 노출해 수정·삭제 메서드를 두지 않는다.
public interface SellerWalletLedgerJpaRepository extends Repository<SellerWalletLedger, Long>, SellerWalletLedgerRepository {
}
