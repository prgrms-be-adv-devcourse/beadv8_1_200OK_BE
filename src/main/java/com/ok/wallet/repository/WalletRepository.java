package com.ok.wallet.repository;

import com.ok.wallet.domain.Wallet;
import com.ok.wallet.domain.WalletType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    boolean existsByMemberIdAndType(Long memberId, WalletType type);
}
