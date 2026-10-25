package com.ok.wallet.service.dto;

import com.ok.wallet.domain.Charge;

// 승인·실패 처리 후 충전 건의 상태. 상태는 컨트롤러가 그대로 쓸 수 있게 문자열로 내보낸다 (컨벤션 2.3).
public record ChargeConfirmResult(String chargeNo, String status, long amount) {
    public static ChargeConfirmResult from(Charge charge) {
        return new ChargeConfirmResult(charge.getChargeNo(), charge.getStatus().name(), charge.getAmount());
    }
}
