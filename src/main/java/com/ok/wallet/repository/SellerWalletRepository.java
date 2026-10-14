package com.ok.wallet.repository;

import com.ok.wallet.domain.SellerWallet;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerWalletRepository extends JpaRepository<SellerWallet, Long> {
    boolean existsByMemberId(Long memberId);
}
