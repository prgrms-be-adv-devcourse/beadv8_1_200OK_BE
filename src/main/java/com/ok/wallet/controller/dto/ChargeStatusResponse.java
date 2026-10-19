package com.ok.wallet.controller.dto;

import com.ok.wallet.service.dto.ChargeConfirmResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "충전 건의 현재 상태")
public record ChargeStatusResponse(
        @Schema(description = "충전 번호") String chargeNo,
        @Schema(description = "충전 상태", example = "COMPLETED",
                allowableValues = {"PENDING", "IN_PROGRESS", "COMPLETED", "FAILED"}) String status,
        @Schema(description = "충전 금액", example = "10000") long amount
) {
    public static ChargeStatusResponse from(ChargeConfirmResult result) {
        return new ChargeStatusResponse(result.chargeNo(), result.status(), result.amount());
    }
}
