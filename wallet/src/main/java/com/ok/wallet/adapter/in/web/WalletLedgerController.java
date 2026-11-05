package com.ok.wallet.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.wallet.adapter.in.web.dto.LedgerHistoryPageResponse;
import com.ok.wallet.adapter.in.web.dto.LedgerHistoryRequest;
import com.ok.wallet.service.WalletLedgerService;
import com.ok.wallet.service.dto.LedgerHistoryQuery;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 회원 ID는 토큰에서 꺼내고, 조회 조건은 쿼리 파라미터를 @Valid로 검증한다.
@PreAuthorize("hasRole('BUYER')")
@RestController
@RequestMapping("/api/v1/wallets/me")
@RequiredArgsConstructor
public class WalletLedgerController implements WalletLedgerControllerSpec {

    private final WalletLedgerService walletLedgerService;

    @Override
    @GetMapping("/ledgers")
    public ResponseEntity<LedgerHistoryPageResponse> getHistory(
            @CurrentMemberId Long memberId,
            @Valid @ModelAttribute LedgerHistoryRequest request
    ) {
        LedgerHistoryQuery query = new LedgerHistoryQuery(
                memberId, request.from(), request.to(), request.type(), request.page(), request.size());
        return ResponseEntity.ok(LedgerHistoryPageResponse.from(walletLedgerService.getHistory(query)));
    }
}
