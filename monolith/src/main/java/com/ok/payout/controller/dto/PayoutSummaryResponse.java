package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "정산 목록의 정산 1건")
public record PayoutSummaryResponse(

        @Schema(description = "정산 ID", example = "12")
        Long payoutId,

        @Schema(description = "이 정산을 확정한 스냅샷 ID", example = "3")
        Long snapshotId,

        @Schema(description = "수취인(판매자) 회원 ID. 시스템 몫이면 null", example = "7", nullable = true)
        Long payeeId,

        @Schema(description = "시스템(플랫폼) 몫 정산 여부. 판매 수수료면 true", example = "false")
        boolean system,

        @Schema(description = "정산 금액 (원)", example = "13000")
        Long amount,

        @Schema(description = "정산 확정 시각", example = "2026-10-15T03:00:00")
        LocalDateTime createdAt
) {

    public static PayoutSummaryResponse from(PayoutSummaryResult result) {
        return new PayoutSummaryResponse(result.payoutId(), result.snapshotId(), result.payeeId(),
                result.system(), result.amount(), result.createdAt());
    }
}
