package com.ok.wallet.service;

import com.ok.wallet.domain.Wallet;
import com.ok.wallet.domain.WalletType;
import com.ok.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository walletRepository;

    @Transactional
    public void createBuyerWallet(Long memberId) {
        if (walletRepository.existsByMemberIdAndType(memberId, WalletType.BUYER)) {
            return;
        }
        walletRepository.save(Wallet.create(memberId, WalletType.BUYER));
    }
}