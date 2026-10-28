package com.ok.wallet.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "동의할 약관 한 건")
public record TermsAgreeItemRequest(

        @Schema(description = "약관 유형", example = "ELECTRONIC_FINANCIAL_TRANSACTION")
        @NotBlank(message = "약관 유형은 필수입니다.")
        String type,

        @Schema(description = "화면에서 확인한 약관 버전", example = "1")
        @NotNull(message = "약관 버전은 필수입니다.")
        @Positive(message = "약관 버전은 1 이상이어야 합니다.")
        Integer version
) {
}
