package com.ok.wallet.controller;

import com.ok.wallet.controller.dto.InternalBuyerBalanceResponse;
import com.ok.wallet.service.BuyerWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// order 모듈이 주문 저장·재고 선점 전에 잔액 부족을 미리 거르려고 HTTP로 호출하는 내부 API.
// 최종 결제 판단은 주문 생성 이벤트를 받아 차감할 때 한다(OrderPaymentService).
// TODO: 인증 없이 회원 ID만으로 잔액이 조회되어 운영에는 등록하지 않는다 (상품 내부 API와 같은 방식).
//  운영에서는 엔진엑스가 /internal 경로를 외부에서 막기로 했으며, 적용되면 상품 내부 API와 함께 @Profile을 제거한다.
@Profile({"dev", "test"})
@RestController
@RequestMapping("/internal/v1/wallets")
@RequiredArgsConstructor
public class WalletInternalController implements WalletInternalControllerSpec {

    private final BuyerWalletService buyerWalletService;

    @Override
    @GetMapping("/buyers/{memberId}/balance")
    public ResponseEntity<InternalBuyerBalanceResponse> getBuyerBalance(@PathVariable Long memberId) {
        return ResponseEntity.ok(new InternalBuyerBalanceResponse(memberId, buyerWalletService.getBalance(memberId)));
    }
}
