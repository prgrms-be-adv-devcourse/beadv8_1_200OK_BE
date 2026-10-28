package com.ok.wallet.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 토스 successUrl로 받은 값을 그대로 전달한다. 토스의 orderId는 chargeNo로 받는다.
@Schema(description = "충전 승인 요청")
public record ChargeConfirmRequest(
        @Schema(description = "충전 번호 (successUrl의 orderId)", example = "CHG_3f2b1c9e-8a4d-4e6f-9b1a-2c3d4e5f6a7b")
        @NotBlank(message = "충전 번호는 필수입니다.")
        @Size(max = 40, message = "충전 번호는 40자 이하여야 합니다.")
        String chargeNo,

        @Schema(description = "토스 결제 키 (successUrl의 paymentKey)", example = "tgen_20261016120000abcde")
        @NotBlank(message = "결제 키는 필수입니다.")
        @Size(max = 200, message = "결제 키는 200자 이하여야 합니다.")
        String paymentKey,

        @Schema(description = "결제 금액 (successUrl의 amount). 서버에 저장된 금액과 다르면 승인하지 않는다.", example = "10000")
        @NotNull(message = "결제 금액은 필수입니다.")
        @Positive(message = "결제 금액은 0보다 커야 합니다.")
        Long amount
) {
}
