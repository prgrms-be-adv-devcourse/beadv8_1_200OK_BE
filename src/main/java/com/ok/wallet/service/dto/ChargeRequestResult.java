package com.ok.wallet.service.dto;

import com.ok.wallet.domain.Charge;

// 토스 결제창 호출에 필요한 충전 번호와 금액. 프론트는 chargeNo를 토스 orderId로 넘긴다.
public record ChargeRequestResult(String chargeNo, long amount) {
    public static ChargeRequestResult from(Charge charge) {
        return new ChargeRequestResult(charge.getChargeNo(), charge.getAmount());
    }
}
