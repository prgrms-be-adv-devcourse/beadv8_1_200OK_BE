package com.ok.wallet.event;

import com.ok.member.MemberRegisteredEvent;
import com.ok.wallet.service.BuyerWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 회원가입 시 역할(role)과 관계없이 모든 회원에게 구매자 지갑을 생성한다.
 * 판매자도 상품을 구매할 수 있으므로 구매자 지갑이 필요하다.
 * 판매자 지갑(SellerWallet)은 별도 엔티티로, 판매자 등록 시 생성한다.
 */
@Component
@RequiredArgsConstructor
public class MemberEventListener {
    private final BuyerWalletService buyerWalletService;

    @ApplicationModuleListener
    void on(MemberRegisteredEvent event) {
        buyerWalletService.create(event.memberId());
    }
}
