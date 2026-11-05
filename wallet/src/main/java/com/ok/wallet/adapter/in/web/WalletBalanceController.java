package com.ok.wallet.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.wallet.adapter.in.web.dto.BuyerBalanceResponse;
import com.ok.wallet.application.port.in.BuyerWalletUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 회원 ID는 @CurrentMemberId가 토큰에서 꺼낸다. 본인 잔액만 조회하므로 경로에 회원 ID를 받지 않는다.
@PreAuthorize("hasRole('BUYER')")
@RestController
@RequestMapping("/api/v1/wallets/me")
@RequiredArgsConstructor
public class WalletBalanceController implements WalletBalanceControllerSpec {

    private final BuyerWalletUseCase buyerWalletUseCase;

    @Override
    @GetMapping("/balance")
    public ResponseEntity<BuyerBalanceResponse> getBalance(@CurrentMemberId Long memberId) {
        return ResponseEntity.ok(BuyerBalanceResponse.from(buyerWalletUseCase.getBalanceSummary(memberId)));
    }
}
