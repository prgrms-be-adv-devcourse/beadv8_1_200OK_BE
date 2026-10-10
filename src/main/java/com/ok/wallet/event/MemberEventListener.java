package com.ok.wallet.event;

import com.ok.member.MemberRegisteredEvent;
import com.ok.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 회원가입 시 역할(role)과 관계없이 모든 회원에게 구매자 지갑을 생성한다.
 * 판매자도 상품을 구매할 수 있으므로 구매자 지갑이 필요하다.
 * 판매자 지갑(SELLER)은 판매자 기능이 정해지면 별도로 생성한다.
 */
@Component
@RequiredArgsConstructor
public class MemberEventListener {
    private final WalletService walletService;

    @ApplicationModuleListener
    void on(MemberRegisteredEvent event) {
        walletService.createBuyerWallet(event.memberId());
    }
}