package com.ok.wallet.event;

import com.ok.member.MemberRegisteredEvent;
import com.ok.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberEventListener {
    private final WalletService walletService;

    @ApplicationModuleListener
    void on(MemberRegisteredEvent event) {
        walletService.createBuyerWallet(event.memberId());
    }
}