package com.ok.wallet.adapter.in.web.dto;

import com.ok.wallet.application.dto.ChargeAdminDetailResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자가 처리를 고르기 전에 보는 충전 건과 토스 결제 상태")
public record ChargeAdminDetailResponse(
        @Schema(description = "충전 건") ChargeAdminResponse charge,
        @Schema(description = "토스 조회 결과. FOUND(결제 있음), NOT_FOUND(결제 없음), UNKNOWN(답을 못 받음)",
                example = "FOUND", allowableValues = {"FOUND", "NOT_FOUND", "UNKNOWN"}) String tossLookup,
        @Schema(description = "토스 결제 상태. 결제가 있을 때만 값이 있다", example = "DONE",
                allowableValues = {"READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT", "DONE", "CANCELED",
                        "PARTIAL_CANCELED", "ABORTED", "EXPIRED"}) String tossStatus,
        @Schema(description = "토스 결제의 최초 금액. 결제가 있을 때만 값이 있다", example = "10000") Long tossTotalAmount
) {
    public static ChargeAdminDetailResponse from(ChargeAdminDetailResult result) {
        return new ChargeAdminDetailResponse(
                ChargeAdminResponse.from(result.charge()),
                result.tossLookup(),
                result.tossStatus(),
                result.tossTotalAmount());
    }
}
