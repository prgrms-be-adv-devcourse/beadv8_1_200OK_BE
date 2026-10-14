package com.ok.wallet.event;

import com.ok.common.event.MemberRegisteredEvent;
import com.ok.common.event.SellerApprovedEvent;
import com.ok.wallet.service.BuyerWalletService;
import com.ok.wallet.service.SellerWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * member 모듈의 이벤트를 수신해 지갑을 생성한다.
 */
@Component
@RequiredArgsConstructor
public class MemberEventListener {
    private final BuyerWalletService buyerWalletService;
    private final SellerWalletService sellerWalletService;

    /**
     * 회원가입 시 구매자 지갑을 생성한다. 모든 회원은 구매자로 가입하며,
     * 판매자도 상품을 구매할 수 있으므로 구매자 지갑을 가진다.
     * 지갑이 이미 있으면 기존 지갑을 그대로 두므로, 같은 이벤트를 여러 번 받아도 지갑은 하나만 생긴다.
     */
    @ApplicationModuleListener
    void on(MemberRegisteredEvent event) {
        buyerWalletService.getOrCreate(event.memberId());
    }

    /**
     * 판매자 승인이 완료되면 판매자 지갑을 생성한다.
     * 판매자 지갑은 회원가입이 아니라 승인 시점에만 생성된다.
     */
    @ApplicationModuleListener
    void on(SellerApprovedEvent event) {
        sellerWalletService.create(event.memberId());
    }
}
