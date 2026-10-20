package com.ok.wallet.controller;

import com.ok.wallet.controller.dto.InternalBuyerBalanceResponse;
import com.ok.wallet.service.BuyerWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// order 모듈이 주문 저장·재고 선점 전에 잔액 부족을 미리 거르려고 HTTP로 호출하는 내부 API.
// 최종 결제 판단은 주문 생성 이벤트를 받아 차감할 때 한다(OrderPaymentService).
// 인증 없이 회원 ID만으로 잔액이 조회되므로, nginx가 /internal/**의 외부 접근을 막고
// 앱도 같은 컨테이너(loopback)에서 온 요청만 받는다(#204).
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
