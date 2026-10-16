package com.ok.wallet.controller.dto;

import com.ok.wallet.service.dto.TermsResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "현재 버전 약관 한 건과 동의 여부")
public record TermsItemResponse(
        @Schema(description = "약관 유형", example = "ELECTRONIC_FINANCIAL_TRANSACTION") String type,
        @Schema(description = "약관 버전", example = "1") int version,
        @Schema(description = "약관 제목") String title,
        @Schema(description = "약관 전문") String content,
        @Schema(description = "필수 여부") boolean required,
        @Schema(description = "현재 버전 동의 여부") boolean agreed
) {
    public static TermsItemResponse from(TermsResult result) {
        return new TermsItemResponse(
                result.type(), result.version(), result.title(),
                result.content(), result.required(), result.agreed());
    }
}
