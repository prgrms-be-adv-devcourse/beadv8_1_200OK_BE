package com.ok.wallet.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 토스 failUrl로 받은 값을 그대로 전달한다.
@Schema(description = "결제 실패 처리 요청")
public record ChargeFailRequest(
        @Schema(description = "충전 번호 (failUrl의 orderId)", example = "CHG_3f2b1c9e-8a4d-4e6f-9b1a-2c3d4e5f6a7b")
        @NotBlank(message = "충전 번호는 필수입니다.")
        @Size(max = 40, message = "충전 번호는 40자 이하여야 합니다.")
        String chargeNo,

        @Schema(description = "토스 오류 코드 (failUrl의 code)", example = "PAY_PROCESS_CANCELED")
        @NotBlank(message = "오류 코드는 필수입니다.")
        @Size(max = 100, message = "오류 코드는 100자 이하여야 합니다.")
        String code,

        @Schema(description = "토스 오류 메시지 (failUrl의 message)", example = "사용자에 의해 결제가 취소되었습니다.")
        @Size(max = 500, message = "오류 메시지는 500자 이하여야 합니다.")
        String message
) {
}
