package com.ok.wallet.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 결제 취소 사유는 토스에도 보내므로 토스 상한(200자)에 맞춘다
@Schema(description = "관리자 실패 처리·결제 취소 요청")
public record ChargeAdminReasonRequest(
        @Schema(description = "사유. 충전 건에 남고, 결제 취소면 토스에도 보낸다 (200자 이하)", example = "토스 결제 확인 결과 승인되지 않음")
        @NotBlank(message = "사유는 필수입니다.")
        @Size(max = 200, message = "사유는 200자 이하여야 합니다.")
        String reason
) {
    public ChargeAdminReasonRequest {
        reason = reason == null ? null : reason.strip();
    }
}
