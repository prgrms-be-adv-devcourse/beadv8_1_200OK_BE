package com.ok.wallet.repository;

import com.ok.wallet.domain.BuyerWallet;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuyerWalletRepository extends JpaRepository<BuyerWallet, Long> {
    boolean existsByMemberId(Long memberId);
}
