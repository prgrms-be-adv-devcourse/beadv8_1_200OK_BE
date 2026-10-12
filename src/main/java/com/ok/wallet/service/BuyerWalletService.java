package com.ok.wallet.service;

import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.repository.BuyerWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BuyerWalletService {
    private final BuyerWalletRepository buyerWalletRepository;

    @Transactional
    public void create(Long memberId) {
        if (buyerWalletRepository.existsByMemberId(memberId)) {
            return;
        }
        buyerWalletRepository.save(BuyerWallet.create(memberId));
    }
}
