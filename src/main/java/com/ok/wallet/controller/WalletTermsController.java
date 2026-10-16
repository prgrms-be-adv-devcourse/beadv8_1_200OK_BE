package com.ok.wallet.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.wallet.controller.dto.TermsAgreeRequest;
import com.ok.wallet.controller.dto.TermsResponse;
import com.ok.wallet.service.TermsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 회원 ID는 @CurrentMemberId가 토큰에서 꺼내고, 요청 본문은 @Valid로 검증한다.
// 경계에서 값을 보장하므로 서비스는 값이 있다고 가정한다.
@RestController
@RequestMapping("/api/v1/wallets/me/terms")
@RequiredArgsConstructor
class WalletTermsController implements WalletTermsControllerSpec {
    private final TermsService termsService;

    @Override
    @GetMapping
    public ResponseEntity<TermsResponse> getTerms(@CurrentMemberId Long memberId) {
        return ResponseEntity.ok(TermsResponse.from(termsService.getTerms(memberId)));
    }

    @Override
    @PostMapping("/agreements")
    public ResponseEntity<Void> agree(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody TermsAgreeRequest request
    ) {
        termsService.agree(memberId, request.toCommands());
        return ResponseEntity.noContent().build();
    }
}
