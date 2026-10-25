package com.ok.wallet.controller.dto;

import com.ok.wallet.service.dto.TermsResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "충전 약관 조회 응답")
public record TermsResponse(
        @Schema(description = "현재 버전 약관 목록") List<TermsItemResponse> terms
) {
    public static TermsResponse from(List<TermsResult> results) {
        return new TermsResponse(results.stream().map(TermsItemResponse::from).toList());
    }
}
