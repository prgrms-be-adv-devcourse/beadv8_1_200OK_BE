package com.ok.wallet.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.wallet.adapter.in.web.dto.ChargeAdminDetailResponse;
import com.ok.wallet.adapter.in.web.dto.ChargeAdminReasonRequest;
import com.ok.wallet.adapter.in.web.dto.ChargeAdminResponse;
import com.ok.wallet.application.port.in.ChargeAdminUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 복구가 끝내지 못한 충전 건을 관리자가 처리한다. 관리자 권한은 @PreAuthorize가 막고,
// 토스 상태와 맞지 않는 선택은 서비스가 처리 직전에 토스에 다시 물어 막는다.
// 처리한 관리자는 토큰에서 꺼내 로그에 남긴다
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/wallets/charges")
@RequiredArgsConstructor
public class WalletChargeAdminController implements WalletChargeAdminControllerSpec {

    private final ChargeAdminUseCase chargeAdminUseCase;

    @Override
    @GetMapping
    public ResponseEntity<List<ChargeAdminResponse>> findTargets() {
        return ResponseEntity.ok(chargeAdminUseCase.findTargets().stream()
                .map(ChargeAdminResponse::from)
                .toList());
    }

    @Override
    @GetMapping("/{chargeNo}")
    public ResponseEntity<ChargeAdminDetailResponse> findDetail(@PathVariable String chargeNo) {
        return ResponseEntity.ok(ChargeAdminDetailResponse.from(chargeAdminUseCase.findDetail(chargeNo)));
    }

    @Override
    @PostMapping("/{chargeNo}/complete")
    public ResponseEntity<ChargeAdminResponse> complete(@CurrentMemberId Long adminId, @PathVariable String chargeNo) {
        return ResponseEntity.ok(ChargeAdminResponse.from(chargeAdminUseCase.complete(adminId, chargeNo)));
    }

    @Override
    @PostMapping("/{chargeNo}/fail")
    public ResponseEntity<ChargeAdminResponse> fail(
            @CurrentMemberId Long adminId,
            @PathVariable String chargeNo,
            @Valid @RequestBody ChargeAdminReasonRequest request
    ) {
        return ResponseEntity.ok(ChargeAdminResponse.from(chargeAdminUseCase.fail(adminId, chargeNo, request.reason())));
    }

    @Override
    @PostMapping("/{chargeNo}/cancel-payment")
    public ResponseEntity<ChargeAdminResponse> cancelPayment(
            @CurrentMemberId Long adminId,
            @PathVariable String chargeNo,
            @Valid @RequestBody ChargeAdminReasonRequest request
    ) {
        return ResponseEntity.ok(ChargeAdminResponse.from(
                chargeAdminUseCase.cancelPayment(adminId, chargeNo, request.reason())));
    }
}
