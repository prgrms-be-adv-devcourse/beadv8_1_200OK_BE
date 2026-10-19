package com.ok.wallet.controller.dto;

import com.ok.wallet.service.dto.ChargeRequestResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "충전 요청 결과. 프론트는 chargeNo를 토스 결제창의 orderId로 넘긴다.")
public record ChargeResponse(
        @Schema(description = "충전 번호 (토스 orderId)", example = "CHG_3f2b1c9e-8a4d-4e6f-9b1a-2c3d4e5f6a7b") String chargeNo,
        @Schema(description = "충전 금액", example = "10000") long amount
) {
    public static ChargeResponse from(ChargeRequestResult result) {
        return new ChargeResponse(result.chargeNo(), result.amount());
    }
}
