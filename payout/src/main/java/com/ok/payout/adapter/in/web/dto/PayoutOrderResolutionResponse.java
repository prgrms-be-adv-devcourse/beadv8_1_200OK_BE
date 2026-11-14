package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutOrderResolutionResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "주문 해결 결과: 방금 저장한 해결 기록 (#568)")
public record PayoutOrderResolutionResponse(

        @Schema(description = "해결한 주문 ID", example = "100")
        Long orderId,

        @Schema(description = "이번에 해결한 실패 기록 수 (해결 기록은 실패 기록마다 1건)", example = "3")
        int resolvedFailureCount,

        @Schema(description = "해결 방법", example = "REPROCESS")
        String method,

        @Schema(description = "해결한 관리자 회원 ID", example = "1")
        Long adminId,

        @Schema(description = "해결 시각", example = "2026-11-14T10:00:00")
        LocalDateTime resolvedAt,

        @Schema(description = "해결 메모", example = "차감액을 9,000원으로 바로잡음")
        String memo
) {

    public static PayoutOrderResolutionResponse from(PayoutOrderResolutionResult result) {
        return new PayoutOrderResolutionResponse(result.orderId(), result.resolvedFailureCount(), result.method(),
                result.adminId(), result.resolvedAt(), result.memo());
    }
}
