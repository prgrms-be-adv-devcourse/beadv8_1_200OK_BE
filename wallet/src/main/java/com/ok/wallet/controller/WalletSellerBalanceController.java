package com.ok.wallet.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.wallet.controller.dto.SellerBalanceResponse;
import com.ok.wallet.service.SellerWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매자만 호출한다. 역할은 토큰의 roles로 확인하고(인가는 각 모듈이 @PreAuthorize로 선언), 회원 ID는 토큰에서 꺼낸다.
@PreAuthorize("hasRole('SELLER')")
@RestController
@RequestMapping("/api/v1/wallets/sellers/me")
@RequiredArgsConstructor
public class WalletSellerBalanceController implements WalletSellerBalanceControllerSpec {

    private final SellerWalletService sellerWalletService;

    @Override
    @GetMapping("/balance")
    public ResponseEntity<SellerBalanceResponse> getBalance(@CurrentMemberId Long memberId) {
        return ResponseEntity.ok(new SellerBalanceResponse(sellerWalletService.getBalance(memberId)));
    }
}
