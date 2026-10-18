package com.ok.wallet.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 금액 규칙(10,000원 이상, 10,000원 단위)은 충전 건이 검증한다. 여기서는 값이 있는지만 본다.
@Schema(description = "예치금 충전 요청")
public record ChargeRequest(
        @Schema(description = "충전 금액 (10,000원 이상, 10,000원 단위)", example = "10000")
        @NotNull(message = "충전 금액은 필수입니다.")
        @Positive(message = "충전 금액은 0보다 커야 합니다.")
        @Max(value = 500_000, message = "충전 금액은 500,000원 이하여야 합니다.")
        Long amount
) {
}
