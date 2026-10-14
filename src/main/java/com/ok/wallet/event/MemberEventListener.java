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
     * 회원가입 시 역할(role)과 관계없이 모든 회원에게 구매자 지갑을 생성한다.
     * 판매자도 상품을 구매할 수 있으므로 구매자 지갑이 필요하다.
     */
    @ApplicationModuleListener
    void on(MemberRegisteredEvent event) {
        buyerWalletService.create(event.memberId());
    }

    /**
     * 판매자 승인이 완료되면 판매자 지갑을 생성한다.
     * 판매자 역할로 가입했더라도 승인 전에는 판매자 지갑을 생성하지 않는다.
     */
    @ApplicationModuleListener
    void on(SellerApprovedEvent event) {
        sellerWalletService.create(event.memberId());
    }
}
