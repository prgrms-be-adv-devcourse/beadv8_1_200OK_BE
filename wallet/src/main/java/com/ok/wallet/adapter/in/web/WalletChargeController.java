package com.ok.wallet.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.wallet.adapter.in.web.dto.ChargeConfirmRequest;
import com.ok.wallet.adapter.in.web.dto.ChargeFailRequest;
import com.ok.wallet.adapter.in.web.dto.ChargeRequest;
import com.ok.wallet.adapter.in.web.dto.ChargeResponse;
import com.ok.wallet.adapter.in.web.dto.ChargeStatusResponse;
import com.ok.wallet.application.port.in.ChargeConfirmUseCase;
import com.ok.wallet.application.port.in.ChargeUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 회원 ID는 @CurrentMemberId가 토큰에서 꺼내고, 요청 본문은 @Valid로 검증한다.
// 경계에서 값을 보장하므로 서비스는 값이 있다고 가정한다.
@PreAuthorize("hasRole('BUYER')")
@RestController
@RequestMapping("/api/v1/wallets/me/charges")
@RequiredArgsConstructor
public class WalletChargeController implements WalletChargeControllerSpec {

    private final ChargeUseCase chargeUseCase;
    private final ChargeConfirmUseCase chargeConfirmUseCase;

    @Override
    @PostMapping
    public ResponseEntity<ChargeResponse> request(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody ChargeRequest request
    ) {
        ChargeResponse response = ChargeResponse.from(chargeUseCase.request(memberId, request.amount()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Override
    @PostMapping("/confirm")
    public ResponseEntity<ChargeStatusResponse> confirm(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody ChargeConfirmRequest request
    ) {
        return ResponseEntity.ok(ChargeStatusResponse.from(chargeConfirmUseCase.confirm(
                memberId, request.chargeNo(), request.paymentKey(), request.amount())));
    }

    @Override
    @PostMapping("/fail")
    public ResponseEntity<ChargeStatusResponse> fail(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody ChargeFailRequest request
    ) {
        return ResponseEntity.ok(ChargeStatusResponse.from(chargeUseCase.fail(
                memberId, request.chargeNo(), request.code(), request.message())));
    }

    @Override
    @GetMapping("/{chargeNo}")
    public ResponseEntity<ChargeStatusResponse> getStatus(
            @CurrentMemberId Long memberId,
            @PathVariable String chargeNo
    ) {
        return ResponseEntity.ok(ChargeStatusResponse.from(chargeUseCase.getStatus(memberId, chargeNo)));
    }
}
